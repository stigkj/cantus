package no.skatteetaten.aurora.cantus.service

import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.catch
import no.skatteetaten.aurora.cantus.ApplicationConfig
import no.skatteetaten.aurora.cantus.controller.CantusException
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import no.skatteetaten.aurora.cantus.execute
import no.skatteetaten.aurora.cantus.setJsonFileAsBody
import okhttp3.Headers
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class DockerRegistryServiceNetworkTest {

    private val server = MockWebServer()
    private val url = server.url("/")

    private val imageRepoCommand = ImageRepoCommand(
        registry = "${url.host()}:${url.port()}",
        imageGroup = "no_skatteetaten_aurora_demo",
        imageName = "whoami",
        imageTag = "2",
        bearerToken = "bearer token"
    )

    private val applicationConfig = ApplicationConfig()

    private val dockerService = DockerRegistryService(
        applicationConfig.webClient(WebClient.builder(), applicationConfig.tcpClient(100, 100, 100, null)),
        RegistryMetadataResolver(listOf(imageRepoCommand.registry)),
        ImageRegistryUrlBuilder()
    )

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @ParameterizedTest
    @ValueSource(ints = [500, 400, 404, 403, 501, 401, 418])
    fun `Get image manifest given internal server error in docker registry`(statusCode: Int) {
        val headers = Headers.of(
            mapOf(
                HttpHeaders.CONTENT_TYPE to MediaType.APPLICATION_JSON_VALUE,
                dockerService.dockerContentDigestLabel to "sha256"
            )
        )
        server.execute(status = statusCode, headers = headers) {
            val exception = catch { dockerService.getImageManifestInformation(imageRepoCommand) }
            assertk.assert(exception).isNotNull {
                assertk.assert(it.actual::class).isEqualTo(SourceSystemException::class)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = SocketPolicy::class,
        names = ["DISCONNECT_AFTER_REQUEST", "DISCONNECT_DURING_RESPONSE_BODY", "NO_RESPONSE"],
        mode = EnumSource.Mode.INCLUDE
    )
    fun `Handle connection failure in retrieve that throws exception`(socketPolicy: SocketPolicy) {

        val response = MockResponse()
            .apply { this.socketPolicy = socketPolicy }
            .setJsonFileAsBody("dockerTagList.json")

        server.execute(response) {
            val exception = catch { dockerService.getImageTags(imageRepoCommand) }
            assertk.assert(exception).isNotNull {
                assertk.assert(it.actual::class).isEqualTo(CantusException::class)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = SocketPolicy::class,
        names = ["DISCONNECT_AFTER_REQUEST", "DISCONNECT_DURING_RESPONSE_BODY", "NO_RESPONSE"],
        mode = EnumSource.Mode.INCLUDE
    )
    fun `Handle connection failure in exchange that throws exception`(socketPolicy: SocketPolicy) {

        val response = MockResponse()
            .setJsonFileAsBody("dockerManifestV1.json")
            .addHeader(dockerService.dockerContentDigestLabel, "SHA::256")
            .apply { this.socketPolicy = socketPolicy }

        server.execute(response) {
            val exception = catch { dockerService.getImageManifestInformation(imageRepoCommand) }
            assertk.assert(exception).isNotNull {
                assertk.assert(it.actual::class).isEqualTo(CantusException::class)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = SocketPolicy::class,
        names = ["KEEP_OPEN", "DISCONNECT_AT_END", "UPGRADE_TO_SSL_AT_END"],
        mode = EnumSource.Mode.INCLUDE
    )
    fun `Handle connection failure that returns response`(socketPolicy: SocketPolicy) {

        val response = MockResponse()
            .apply { this.socketPolicy = socketPolicy }
            .setJsonFileAsBody("dockerTagList.json")

        server.execute(response) {
            val result = dockerService.getImageTags(imageRepoCommand)
            assertk.assert(result).isNotNull()
        }
    }
}