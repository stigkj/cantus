package no.skatteetaten.aurora.cantus.service

import assertk.Assert
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.message
import assertk.catch
import no.skatteetaten.aurora.cantus.ApplicationConfig
import no.skatteetaten.aurora.cantus.AuroraIntegration.AuthType.Bearer
import no.skatteetaten.aurora.cantus.controller.CantusException
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import no.skatteetaten.aurora.cantus.createObjectMapper
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.execute
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.setJsonFileAsBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.core.publisher.toMono

class DockerHttpClientTest {
    private val server = MockWebServer()
    private val url = server.url("/")

    private val imageRepoCommand = ImageRepoCommand(
        registry = "${url.host}:${url.port}",
        imageGroup = "no_skatteetaten_aurora_demo",
        imageName = "whoami",
        imageTag = "2",
        token = "bearer token",
        authType = Bearer,
        url = "http://${url.host}:${url.port}/v2"
    )

    private val applicationConfig = ApplicationConfig()

    private val httpClient = DockerHttpClient(
        applicationConfig.webClient(
            WebClient.builder(),
            applicationConfig.tcpClient(100, 100, 100, null),
            "cantus",
            "123"
        )
    )

    @Test
    fun `verify upload layer will retry`() {

        val content: Mono<ByteArray> = "this is teh content".toByteArray().toMono()

        val fail = MockResponse().setResponseCode(404)
        // Any response will do here.
        val response =
            MockResponse()
                .setJsonFileAsBody("dockerManifestV2Config.json")
                .addHeader("Docker-Content-Digest", "SHA::256")

        val requests = server.execute(fail, fail, fail, response) {
            val result = httpClient.uploadLayer(imageRepoCommand, "uuid", "digest", content)
            assertThat(result).isTrue()
        }
        assertThat(requests).retries(3)
    }

    @Test

    fun `verify upload layer will retry and fail after 4 times`() {

        val content: Mono<ByteArray> = "this is teh content".toByteArray().toMono()

        val fail = MockResponse().setResponseCode(404)

        val requests = server.execute(fail, fail, fail, fail) {
            val exception = catch { httpClient.uploadLayer(imageRepoCommand, "uuid", "digest", content) }
            assertThat(exception)
                .isNotNull().isInstanceOf(CantusException::class)
                .message().isNotNull()
                .contains("Retry failed")
        }
        assertThat(requests).retries(3)
    }

    @Test
    fun `verify upload layer`() {
        val content: Mono<ByteArray> = "this is teh content".toByteArray().toMono()

        // Any response will do here.
        val response =
            MockResponse()
                .setJsonFileAsBody("dockerManifestV2Config.json")
                .addHeader("Docker-Content-Digest", "SHA::256")

        server.execute(response) {
            val result = httpClient.uploadLayer(imageRepoCommand, "uuid", "digest", content)
            assertThat(result).isTrue()
        }
    }

    @Test
    fun `test put manifest failed`() {

        val manifest = ImageManifestResponseDto(manifestV2, "abc", createObjectMapper().readTree("{}"))

        val response = MockResponse().setResponseCode(404)
            .setBody("{\"errors\":[{\"code\":\"BLOB_UNKNOWN\",\"message\":\"blob unknown to registry\",\"detail\":\"sha256:303510ed0dee065d6dc0dd4fbb1833aa27ff6177e7dfc72881ea4ea0716c82a1\"}]}")
        server.execute(response, response, response, response) {
            val exception = catch { httpClient.putManifest(imageRepoCommand, manifest) }
            assertThat(exception)
                .isNotNull().isInstanceOf(SourceSystemException::class)
                .message().isNotNull()
                .contains("cause=NotFound lastError=404 Not Found operation=PUT_MANIFEST")
        }
    }

    @Test
    fun `test digest authentication failed`() {
        val response = MockResponse().setResponseCode(401).setBody("Unauthorized")
        server.execute(response, response, response, response) {
            val exception = catch { httpClient.digestExistInRepo(imageRepoCommand, "abc") }
            assertThat(exception)
                .isNotNull().isInstanceOf(SourceSystemException::class)
                .message().isNotNull()
                .contains("cause=Unauthorized lastError=401 Unauthorized operation=BLOB_EXIST")
        }
    }

    @Test
    fun `test digest does not exist in repo`() {
        val response = MockResponse().setResponseCode(404)
        server.execute(response) {
            val result = httpClient.digestExistInRepo(imageRepoCommand, "abc")
            assertThat(result).isFalse()
        }
    }

    @Test
    fun `test digest exist in repo`() {
        server.execute(
            MockResponse()
                .setResponseCode(200)
        ) {
            val result = httpClient.digestExistInRepo(imageRepoCommand, "abc")
            assertThat(result).isTrue()
        }
    }

    @Test
    fun `Verify get upload UUID header`() {
        val header = "abcas1456"
        val response = MockResponse().addHeader(uploadUUIDHeader, header)

        server.execute(response) {
            val header = httpClient.getUploadUUID(imageRepoCommand)
            assertThat(header).isEqualTo(header)
        }
    }

    @Test
    fun `Verify fetches blob`() {
        val response = MockResponse().setBody("This is a test")

        server.execute(response) {
            val blob = httpClient.getLayer(imageRepoCommand, "SHA::256").block()
            assertThat(blob).isNotNull()
        }
    }

    @Test
    fun `Verify fetch layer`() {
        val response = MockResponse().setBody("foobar")

        server.execute(response) {
            val jsonResponse = httpClient.getLayer(imageRepoCommand, "SHA::256").block()
            assertThat(jsonResponse).isNotNull()
        }
    }

    @Test
    fun `Verify fetch config throw exception on failure`() {
        val response =
            MockResponse()
                .setResponseCode(404)

        server.execute(response, response, response, response) {
            val exception = catch { httpClient.getConfig(imageRepoCommand, "SHA::256") }

            assertThat(exception).isNotNull().isInstanceOf(SourceSystemException::class)
        }
    }

    @Test
    fun `Verify fetch config`() {
        val response =
            MockResponse()
                .setJsonFileAsBody("dockerManifestV2Config.json")
                .addHeader("Docker-Content-Digest", "SHA::256")

        server.execute(response) {
            val jsonResponse = httpClient.getConfig(imageRepoCommand, "SHA::256")
            assertThat(jsonResponse).isNotNull()
        }
    }

    @Test
    fun `Verify fetches manifest information for specified image`() {
        val response =
            MockResponse()
                .setJsonFileAsBody("dockerManifestV1.json")
                .addHeader("Docker-Content-Digest", "SHA::256")
                .setHeader("Content-Type", MediaType.valueOf(manifestV2))

        server.execute(response) {
            val jsonResponse = httpClient.getImageManifest(imageRepoCommand)
            assertThat(jsonResponse).isNotNull().given {
                assertThat(it.contentType).contains("v2+json")
                assertThat(it.dockerContentDigest).isEqualTo("SHA::256")
                assertThat(it.manifestBody).isNotNull()
            }
        }
    }

    @Test
    fun `Verify fetches all tags for specified image`() {
        val response = MockResponse().setJsonFileAsBody("dockerTagList.json")

        server.execute(response) {
            val jsonResponse: ImageTagsResponseDto? = httpClient.getImageTags(imageRepoCommand)
            assertThat(jsonResponse).isNotNull().given {
                assertThat(it.tags.size).isEqualTo(5)
                assertThat(it.tags[0]).isEqualTo("0")
                assertThat(it.tags[1]).isEqualTo("0.0")
                assertThat(it.tags[2]).isEqualTo("0.0.0")
            }
        }
    }

    @Test
    fun `Verify that empty manifest response throws SourceSystemException`() {
        val response = MockResponse().addHeader(dockerContentDigestLabel, "sha::256")

        server.execute(response) {
            val exception = catch { httpClient.getImageManifest(imageRepoCommand) }

            assertThat(exception).isNotNull().isInstanceOf(SourceSystemException::class)
        }
    }

    @Test
    fun `Verify that non existing Docker-Content-Digest throws SourceSystemException`() {
        val response = MockResponse()
            .setJsonFileAsBody("dockerManifestV1.json")

        server.execute(response) {
            val exception = catch { httpClient.getImageManifest(imageRepoCommand) }

            assertThat(exception)
                .isNotNull().isInstanceOf(SourceSystemException::class)
                .message().isNotNull()
                .contains("Only v2 manifest is supported. contentType=application/json;charset=UTF-8")
        }
    }

    @Test
    fun `Verify that if V2 content type is set then retrieve manifest with V2 method`() {
        val response = MockResponse()
            .setJsonFileAsBody("dockerManifestV2.json")
            .setHeader("Content-Type", "application/vnd.docker.distribution.manifest.v2+json")
            .addHeader("Docker-Content-Digest", "sha256")

        server.execute(response) {

            val dto = httpClient.getImageManifest(imageRepoCommand)

            assertThat(dto).isNotNull().given {
                assertThat(it.contentType).contains("v2")
                assertThat(it.dockerContentDigest).isEqualTo("sha256")
                assertThat(it.manifestBody).isNotNull()
            }
        }
    }

    private fun Assert<List<RecordedRequest?>>.retries(retries: Int) = given { requests ->
        assertThat(requests).hasSize(retries + 1)
        val firstPath = requests.first()?.path
        for (i in 1..retries) {
            assertThat(firstPath).isEqualTo(requests[i]?.path)
        }
    }
}
