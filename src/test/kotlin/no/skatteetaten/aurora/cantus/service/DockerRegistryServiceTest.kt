package no.skatteetaten.aurora.cantus.service

import assertk.assert
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.catch
import io.mockk.clearMocks
import no.skatteetaten.aurora.cantus.controller.BadRequestException
import no.skatteetaten.aurora.cantus.controller.DockerRegistryException
import no.skatteetaten.aurora.cantus.execute
import no.skatteetaten.aurora.cantus.setJsonFileAsBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.web.client.RestTemplate

class DockerRegistryServiceTest {

    private val imageRepoName = "no_skatteetaten_aurora_demo/whoami"
    private val tagName = "2"

    private val server = MockWebServer()
    private val url = server.url("/")
    private val allowedUrls = listOf("docker-registry.no", "internal-docker-registry.no")
    private val dockerService = DockerRegistryService(RestTemplate(), url.toString(), listOf(url.toString()))

    @BeforeEach
    fun setUp() {
        clearMocks()
    }

    @Test
    fun `Verify fetches manifest information for specified image`() {
        val response =
            MockResponse().setJsonFileAsBody("dockerManifest.json").addHeader("Docker-Content-Digest", "SHA::256")

        val requests = server.execute(response, response) {
            val jsonResponse = dockerService.getImageManifestInformation(imageRepoName, tagName)
            assert(jsonResponse).isNotNull {
                assert(it.actual.size).isEqualTo(10)
                assert(it.actual["DOCKER-CONTENT-DIGEST"]).isEqualTo("SHA::256")
                assert(it.actual["DOCKER_VERSION"]).isEqualTo("1.13.1")
                assert(it.actual["CREATED"]).isEqualTo("2018-11-05T14:01:22.654389192Z")
            }
        }
        assert(requests.size).isEqualTo(2)
    }

    @Test
    fun `Verify fetches all tags for specified image`() {
        val response = MockResponse().setJsonFileAsBody("dockerTagList.json")

        server.execute(response) {
            val jsonResponse = dockerService.getImageTags(imageRepoName)
            assert(jsonResponse).isNotNull {
                assert(it.actual.size).isEqualTo(5)
                assert(it.actual[0]).isEqualTo("0")
                assert(it.actual[1]).isEqualTo("0.0")
                assert(it.actual[2]).isEqualTo("0.0.0")
            }
        }
    }

    @Test
    fun `Verify groups tags correctly`() {
        val response = MockResponse().setJsonFileAsBody("dockerTagList.json")

        server.execute(response) {
            val jsonResponse = dockerService.getImageTagsGroupedBySemanticVersion(imageRepoName)
            assert(jsonResponse).isNotNull {
                assert(it.actual["BUGFIX"]?.size).isEqualTo(2)
                assert(it.actual["MINOR"]?.first()).isEqualTo("0.0")
                assert(it.actual.size).isEqualTo(4)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [500, 400, 404])
    fun `Get image manifest given internal server error in docker registry`(statusCode: Int) {
        server.execute(statusCode) {
            val exception = catch { dockerService.getImageManifestInformation(imageRepoName, tagName) }
            assert(exception).isNotNull {
                assert(it.actual::class).isEqualTo(DockerRegistryException::class)
            }
        }
    }

    @Test
    fun `Verify that disallowed docker registry url returns bad request error`() {
        val dockerServiceTestDisallowed = DockerRegistryService(RestTemplate(), url.toString(), allowedUrls)

        server.execute {
            val exception = catch { dockerServiceTestDisallowed.getImageManifestInformation(imageRepoName, tagName) }
            assert(exception).isNotNull {
                assert(it.actual::class).isEqualTo(BadRequestException::class)
                assert(it.actual.message).isEqualTo("Invalid Docker Registry URL")
            }
        }
    }
}
