package no.skatteetaten.aurora.cantus.service

import assertk.assertThat
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import no.skatteetaten.aurora.cantus.ApplicationConfig
import no.skatteetaten.aurora.cantus.AuroraIntegration.AuthType.Bearer
import no.skatteetaten.aurora.cantus.controller.CantusException
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.execute
import no.skatteetaten.aurora.mockmvc.extensions.mockwebserver.setJsonFileAsBody
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
class DockerHttpClientNetworkTest {

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

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @ParameterizedTest
    @ValueSource(ints = [500, 400, 404, 403, 501, 401, 418])
    fun `Get image manifest given internal server error in docker registry`(statusCode: Int) {

        val mockResponse = MockResponse()
            .setResponseCode(statusCode)
            .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .addHeader(dockerContentDigestLabel, "sha256")

        server.execute(mockResponse) {
            assertThat { httpClient.getImageManifest(imageRepoCommand) }
                .isFailure()
                .isNotNull()
                .isInstanceOf(SourceSystemException::class)
        }
    }

    // Add STALL_SOCKET_AT_START to test ReadTimeoutException
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
            assertThat { httpClient.getImageTags(imageRepoCommand) }
                .isFailure()
                .isNotNull()
                .isInstanceOf(CantusException::class)
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
            .addHeader(dockerContentDigestLabel, "SHA::256")
            .apply { this.socketPolicy = socketPolicy }

        server.execute(response) {
            assertThat { httpClient.getImageManifest(imageRepoCommand) }
                .isFailure().isNotNull().isInstanceOf(CantusException::class)
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
            val result = httpClient.getImageTags(imageRepoCommand)

            assertThat(result).isNotNull()
        }
    }
}