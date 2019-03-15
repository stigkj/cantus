package no.skatteetaten.aurora.cantus.service

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.message
import assertk.catch
import no.skatteetaten.aurora.cantus.ApplicationConfig
import no.skatteetaten.aurora.cantus.controller.ForbiddenException
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import no.skatteetaten.aurora.cantus.execute
import no.skatteetaten.aurora.cantus.setJsonFileAsBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

class DockerRegistryServiceTest {
    private val server = MockWebServer()
    private val url = server.url("/")

    private val imageRepoCommand = ImageRepoCommand(
        registry = "${url.host()}:${url.port()}",
        imageGroup = "no_skatteetaten_aurora_demo",
        imageName = "whoami",
        imageTag = "2",
        bearerToken = "bearer token"
    )

    private val dockerServiceNoBearer = DockerRegistryService(
        WebClient.create(),
        RegistryMetadataResolver(listOf("noBearerToken.com")),
        ImageRegistryUrlBuilder()
    )

    private val imageRepoCommandNoToken =
        ImageRepoCommand("noBearerToken.com", "no_skatteetaten_aurora_demo", "whoami", "2")

    private val applicationConfig = ApplicationConfig()

    private val dockerService = DockerRegistryService(
        applicationConfig.webClient(WebClient.builder(), applicationConfig.tcpClient(100, 100, 100, null), "cantus"),
        RegistryMetadataResolver(listOf(imageRepoCommand.registry)),
        ImageRegistryUrlBuilder()
    )

    @Test
    fun `Verify fetches manifest information for specified image`() {
        val response =
            MockResponse().setJsonFileAsBody("dockerManifestV1.json").addHeader("Docker-Content-Digest", "SHA::256")

        server.execute(response) {
            val jsonResponse = dockerService.getImageManifestInformation(imageRepoCommand)
            assertThat(jsonResponse).isNotNull().given {
                assertThat(it.dockerDigest).isEqualTo("SHA::256")
                assertThat(it.dockerVersion).isEqualTo("1.13.1")
                assertThat(it.buildEnded).isEqualTo("2018-11-05T14:01:22.654389192Z")
            }
        }
    }

    @Test
    fun `Verify fetches all tags for specified image`() {
        val response = MockResponse().setJsonFileAsBody("dockerTagList.json")

        server.execute(response) {
            val jsonResponse: ImageTagsWithTypeDto = dockerService.getImageTags(imageRepoCommand)
            assertThat(jsonResponse).isNotNull().given {
                assertThat(it.tags.size).isEqualTo(5)
                assertThat(it.tags[0].name).isEqualTo("0")
                assertThat(it.tags[1].name).isEqualTo("0.0")
                assertThat(it.tags[2].name).isEqualTo("0.0.0")
            }
        }
    }

    @Test
    fun `Verify that empty tag list throws SourceSystemException`() {
        server.execute(ImageTagsResponseDto(emptyList())) {
            val exception = catch { dockerService.getImageTags(imageRepoCommand) }

            assertThat(exception)
                .isNotNull().isInstanceOf(SourceSystemException::class)
                .message().isNotNull().contains("status=404 message=Not Found")
        }
    }

    @Test
    fun `Verify that empty manifest response throws SourceSystemException`() {
        val response = MockResponse().addHeader(dockerService.dockerContentDigestLabel, "sha::256")

        server.execute(response) {
            val exception = catch { dockerService.getImageManifestInformation(imageRepoCommand) }

            assertThat(exception).isNotNull().isInstanceOf(SourceSystemException::class)
        }
    }

    @Test
    fun `Verify that empty body throws SourceSystemException`() {
        val response = MockResponse()
            .setJsonFileAsBody("emptyDockerManifestV1.json")
            .addHeader("Docker-Content-Digest", "SHA::256")

        server.execute(response) {
            val exception = catch { dockerService.getImageManifestInformation(imageRepoCommand) }

            assertThat(exception).isNotNull().isInstanceOf(SourceSystemException::class)
        }
    }

    @Test
    fun `Verify that non existing Docker-Content-Digest throws SourceSystemException`() {
        val response = MockResponse()
            .setJsonFileAsBody("dockerManifestV1.json")

        server.execute(response) {
            val exception = catch { dockerService.getImageManifestInformation(imageRepoCommand) }

            assertThat(exception)
                .isNotNull().isInstanceOf(SourceSystemException::class)
                .message().isNotNull().contains("Response did not contain")
        }
    }

    @Test
    fun `Get image tags given missing authorization token throws ForbiddenException`() {
        val exception = catch { dockerServiceNoBearer.getImageTags(imageRepoCommandNoToken) }
        assertThat(exception)
            .isNotNull().isInstanceOf(ForbiddenException::class)
            .message().isNotNull().isEqualTo("Authorization bearer token is not present")
    }

    @Test
    fun `Get image manifest given missing authorization token throws ForbiddenException`() {
        val exception = catch { dockerServiceNoBearer.getImageManifestInformation(imageRepoCommandNoToken) }
        assertThat(exception)
            .isNotNull().isInstanceOf(ForbiddenException::class)
            .message().isNotNull().isEqualTo("Authorization bearer token is not present")
    }

    @Test
    fun `Verify that if V2 content type is set then retrieve manifest with V2 method`() {
        val response = MockResponse()
            .setJsonFileAsBody("dockerManifestV2.json")
            .setHeader("Content-Type", "application/vnd.docker.distribution.manifest.v2+json")
            .addHeader("Docker-Content-Digest", "sha256")

        val response2 = MockResponse()
            .setJsonFileAsBody("dockerManifestV2Config.json")

        val requests = server.execute(response, response2) {

            val jsonResponse = dockerService.getImageManifestInformation(imageRepoCommand)

            assertThat(jsonResponse).isNotNull().given {
                assertThat(it.dockerDigest).isEqualTo("sha256")
                assertThat(it.nodeVersion).isEqualTo(null)
                assertThat(it.buildEnded).isEqualTo("2018-11-05T14:01:22.654389192Z")
                assertThat(it.java?.major).isEqualTo("8")
            }
        }
        assertThat(requests.size).isEqualTo(2)
    }

    @Test
    fun `Verify that V2 manifest not found is handled`() {
        val response = MockResponse()
            .setJsonFileAsBody("dockerManifestV2.json")
            .setHeader("Content-Type", "application/vnd.docker.distribution.manifest.v2+json")
            .addHeader("Docker-Content-Digest", "sha256")

        val response2 = MockResponse()

        val requests = server.execute(response, response2) {
            val exception = catch { dockerService.getImageManifestInformation(imageRepoCommand) }

            assertThat(exception)
                .isNotNull().isInstanceOf(SourceSystemException::class)
                .message().isNotNull().contains("Unable to retrieve V2 manifest")
        }
        assertThat(requests.size).isEqualTo(2)
    }
}
