package no.skatteetaten.aurora.cantus.service

import assertk.Assert
import assertk.assert
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
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
        applicationConfig.webClient(WebClient.builder(), applicationConfig.tcpClient(100, 100, 100)),
        RegistryMetadataResolver(listOf(imageRepoCommand.registry)),
        ImageRegistryUrlBuilder()
    )

    @Test
    fun `Verify fetches manifest information for specified image`() {
        val response =
            MockResponse().setJsonFileAsBody("dockerManifestV1.json").addHeader("Docker-Content-Digest", "SHA::256")

        server.execute(response) {
            val jsonResponse = dockerService.getImageManifestInformation(imageRepoCommand)
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
            val jsonResponse: ImageTagsWithTypeDto = dockerService.getImageTags(imageRepoCommand)
            assert(jsonResponse).isNotNull {
                assert(it.actual.tags.size).isEqualTo(5)
                assert(it.actual.tags[0].name).isEqualTo("0")
                assert(it.actual.tags[1].name).isEqualTo("0.0")
                assert(it.actual.tags[2].name).isEqualTo("0.0.0")
            }
        }
    }

    @Test
    fun `Verify that empty tag list throws SourceSystemException`() {
        server.execute(ImageTagsResponseDto(emptyList())) {
            val exception = catch { dockerService.getImageTags(imageRepoCommand) }

            assert(exception).isNotNull {
                assert(it.actual::class).isEqualTo(SourceSystemException::class)
                assert(it.actual.message).isEqualTo("Tags not found for image ${imageRepoCommand.defaultRepo}")
            }
        }
    }

    @Test
    fun `Verify that empty manifest response throws SourceSystemException`() {
        val response = MockResponse().addHeader(dockerService.dockerContentDigestLabel, "sha::256")

        server.execute(response) {
            val exception = catch { dockerService.getImageManifestInformation(imageRepoCommand) }

            assert(exception).isNotNull {
                assert(it.actual::class).isEqualTo(SourceSystemException::class)
            }
        }
    }

    @Test
    fun `Verify that empty body throws SourceSystemException`() {
        val response = MockResponse()
            .setJsonFileAsBody("emptyDockerManifestV1.json")
            .addHeader("Docker-Content-Digest", "SHA::256")

        server.execute(response) {
            val exception = catch { dockerService.getImageManifestInformation(imageRepoCommand) }

            assert(exception).isNotNull {
                assert(it.actual::class).isEqualTo(SourceSystemException::class)
            }
        }
    }

    @Test
    fun `Verify that non existing Docker-Content-Digest throws SourceSystemException`() {
        val response = MockResponse()
            .setJsonFileAsBody("dockerManifestV1.json")

        server.execute(response) {
            val exception = catch { dockerService.getImageManifestInformation(imageRepoCommand) }

            assert(exception).isNotNull {
                assert(it.actual::class).isEqualTo(SourceSystemException::class)
                assert(it.actual).beginsWith("Response did not contain")
            }
        }
    }

    @Test
    fun `Get image tags given missing authorization token throws ForbiddenException`() {
        val exception = catch { dockerServiceNoBearer.getImageTags(imageRepoCommandNoToken) }
        assert(exception).isNotNull {
            assert(it.actual::class).isEqualTo(ForbiddenException::class)
            assert(it.actual.message).isEqualTo("Authorization bearer token is not present")
        }
    }

    @Test
    fun `Get image manifest given missing authorization token throws ForbiddenException`() {
        val exception = catch { dockerServiceNoBearer.getImageManifestInformation(imageRepoCommandNoToken) }
        assert(exception).isNotNull {
            assert(it.actual::class).isEqualTo(ForbiddenException::class)
            assert(it.actual.message).isEqualTo("Authorization bearer token is not present")
        }
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

            assert(jsonResponse).isNotNull {
                assert(it.actual.dockerDigest).isEqualTo("sha256")
                assert(it.actual.nodeVersion).isEqualTo(null)
                assert(it.actual.buildEnded).isEqualTo("2018-11-05T14:01:22.654389192Z")
                assert(it.actual.java?.major).isEqualTo("8")
            }
        }
        assert(requests.size).isEqualTo(2)
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

            assert(exception).isNotNull {
                assert(it.actual::class).isEqualTo(SourceSystemException::class)
                assert(it.actual).beginsWith("Unable to retrieve Vl1 manifest")
            }
        }
        assert(requests.size).isEqualTo(2)
    }

    private fun Assert<Throwable>.beginsWith(subString: String) = actual.message?.startsWith(subString)
}
