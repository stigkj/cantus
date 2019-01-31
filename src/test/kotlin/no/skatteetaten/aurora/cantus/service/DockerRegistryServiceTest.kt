package no.skatteetaten.aurora.cantus.service

import assertk.assert
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.catch
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import no.skatteetaten.aurora.cantus.execute
import no.skatteetaten.aurora.cantus.setJsonFileAsBody
import okhttp3.Headers
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient

class DockerRegistryServiceTest {
    private val server = MockWebServer()
    private val url = server.url("/")

    private val imageRepoDto = ImageRepoCommand(
        registry = "${url.host()}:${url.port()}",
        imageGroup = "no_skatteetaten_aurora_demo",
        imageName = "whoami",
        imageTag = "2",
        bearerToken = "bearer token"
    )

    private val dockerService = DockerRegistryService(
        WebClient.create(),
        RegistryMetadataResolver(listOf(imageRepoDto.registry)),
        ImageRegistryUrlBuilder()
    )

    @Test
    fun `Verify fetches manifest information for specified image`() {
        val response =
            MockResponse().setJsonFileAsBody("dockerManifestV1.json").addHeader("Docker-Content-Digest", "SHA::256")

        server.execute(response) {
            val jsonResponse = dockerService.getImageManifestInformation(imageRepoDto)
            assert(jsonResponse).isNotNull {
                assert(it.actual.dockerDigest).isEqualTo("SHA::256")
                assert(it.actual.dockerVersion).isEqualTo("1.13.1")
                assert(it.actual.buildEnded).isEqualTo("2018-11-05T14:01:22.654389192Z")
            }
        }
    }

    @Test
    fun `Verify fetches all tags for specified image`() {
        val response = MockResponse().setJsonFileAsBody("dockerTagList.json")

        server.execute(response) {
            val jsonResponse: ImageTagsWithTypeDto = dockerService.getImageTags(imageRepoDto)
            assert(jsonResponse).isNotNull {
                assert(it.actual.tags.size).isEqualTo(5)
                assert(it.actual.tags[0].name).isEqualTo("0")
                assert(it.actual.tags[1].name).isEqualTo("0.0")
                assert(it.actual.tags[2].name).isEqualTo("0.0.0")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [500, 400, 404])
    fun `Get image manifest given internal server error in docker registry`(statusCode: Int) {
        val headers = Headers.of(
            mapOf(
                HttpHeaders.CONTENT_TYPE to MediaType.APPLICATION_JSON_VALUE,
                "Docker-Content-Digest" to "abc123"
            )
        )
        server.execute(status = statusCode, headers = headers) {
            val exception = catch { dockerService.getImageManifestInformation(imageRepoDto) }
            assert(exception).isNotNull {
                assert(it.actual::class).isEqualTo(SourceSystemException::class)
            }
        }
    }

    @Test
    fun `Verify that empty tag list throws SourceSystemException`() {
        server.execute(ImageTagsResponseDto(emptyList())) {
            val exception = catch { dockerService.getImageTags(imageRepoDto) }

            assert(exception).isNotNull {
                assert(it.actual::class).isEqualTo(SourceSystemException::class)
                assert(it.actual.message).isEqualTo("Tags not found for image ${imageRepoDto.defaultRepo}")
            }
        }
    }

/* TODO: Flytt til controller test

    @Test
    fun `Verify that disallowed docker registry url returns bad request error`() {
        val dockerServiceTestDisallowed = DockerRegistryService(WebClient.create(), url.toString(), allowedUrls)

        server.execute {
            val exception =
                catch { dockerServiceTestDisallowed.getImageManifestInformation(imageRepoDto) }
            assert(exception).isNotNull {
                assert(it.actual::class).isEqualTo(BadRequestException::class)
                assert(it.actual.message).isEqualTo("Invalid Docker Registry URL")
            }
        }
    }
*/

    @Test
    fun `Verify that if V2 content type is set then retrieve manifest with V2 method`() {
        val response = MockResponse()
            .setJsonFileAsBody("dockerManifestV2.json")
            .setHeader("Content-Type", "application/vnd.docker.distribution.manifest.v2+json")
            .addHeader("Docker-Content-Digest", "sha256")

        val response2 = MockResponse()
            .setJsonFileAsBody("dockerManifestV2Config.json")

        val requests = server.execute(response, response2) {

            val jsonResponse = dockerService.getImageManifestInformation(imageRepoDto)

            assert(jsonResponse).isNotNull {
                assert(it.actual.dockerDigest).isEqualTo("sha256")
                assert(it.actual.nodeVersion).isEqualTo(null)
                assert(it.actual.buildEnded).isEqualTo("2018-11-05T14:01:22.654389192Z")
                assert(it.actual.java?.major).isEqualTo("8")
            }
        }
        assert(requests.size).isEqualTo(2)
    }
}
