package no.skatteetaten.aurora.cantus.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nhaarman.mockito_kotlin.any
import kotlinx.coroutines.newFixedThreadPoolContext
import no.skatteetaten.aurora.cantus.AuroraIntegration
import no.skatteetaten.aurora.cantus.ImageTagsWithTypeDtoBuilder
import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.contentType
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.post
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.RequestBuilder
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder

private const val defaultTestRegistry: String = "docker.com"

@WebMvcTest(
    value = [
        DockerRegistryController::class,
        AuroraResponseAssembler::class,
        ImageTagResourceAssembler::class,
        ImageRepoCommandAssembler::class,
        AuroraIntegration::class
    ],
    secure = false
)
class DockerRegistryControllerTest {

    @TestConfiguration
    class DockerRegistryControllerTestConfiguration {

        @Bean
        fun threadPoolContext(@Value("\${cantus.threadPoolSize:6}") threadPoolSize: Int) =
            newFixedThreadPoolContext(threadPoolSize, "cantus")
    }

    @MockBean
    private lateinit var dockerService: DockerRegistryService

    @Autowired
    private lateinit var mockMvc: MockMvc

    private val tags = ImageTagsWithTypeDtoBuilder("no_skatteetaten_aurora_demo", "whoami").build()

    @Test
    fun `Get request given invalid repoUrl throw BadRequestException when missing registryUrl`() {
        val path = "/tags?repoUrl=no_skatteetaten_aurora_demo/whaomi"
        val repoUrl = path.split("=")[1]

        mockMvc.get(Path(path)) {
            statusIsOk()
                .responseJsonPath("$.items").isEmpty()
                .responseJsonPath("$.success").isFalse()
                .responseJsonPath("$.failure[0].url").equalsValue(repoUrl)
                .responseJsonPath("$.failure[0].errorMessage")
                .equalsValue("repo url=no_skatteetaten_aurora_demo/whaomi malformed pattern=url:port/group/name:tag")
        }
    }

    @Test
    fun `Get request given invalid repoUrl throw BadRequestException when missing name`() {

        val path = "/tags/semantic?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora"
        val repoUrl = path.split("=")[1]

        mockMvc.get(Path(path)) {
            statusIsOk()
                .responseJsonPath("$.items").isEmpty()
                .responseJsonPath("$.success").isFalse()
                .responseJsonPath("$.failure[0].url").equalsValue(repoUrl)
                .responseJsonPath("$.failure[0].errorMessage")
                .equalsValue("repo url=docker.com/no_skatteetaten_aurora malformed pattern=url:port/group/name:tag")
        }
    }

    @Test
    fun `Get docker registry image manifest with POST given missing resources`() {
        val tagUrlsWrapper = TagUrlsWrapper(listOf("$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/2"))

        val notFoundStatus = HttpStatus.NOT_FOUND

        given(dockerService.getImageManifestInformation(any())).willThrow(
            SourceSystemException(
                message = "Resource could not be found status=${notFoundStatus.value()} message=${notFoundStatus.reasonPhrase}",
                sourceSystem = "https://docker.com"
            )
        )

        mockMvc.post(
            path = Path("/manifest"),
            headers = HttpHeaders().contentType(),
            body = tagUrlsWrapper
        ) {
            statusIsOk()
                .responseJsonPath("$.success").isFalse()
                .responseJsonPath("$.items").isEmpty()
                .responseJsonPath("$.failure[0].url").equalsValue(tagUrlsWrapper.tagUrls.first())
                .responseJsonPath("$.failure[0].errorMessage")
                .equalsValue("Resource could not be found status=${notFoundStatus.value()} message=${notFoundStatus.reasonPhrase}")
        }
    }

    @Test
    fun `Post request given invalid tagUrl in body`() {
        val tagUrlsWrapper = TagUrlsWrapper(listOf(""))

        given(dockerService.getImageManifestInformation(any()))
            .willThrow(BadRequestException("Invalid url=${tagUrlsWrapper.tagUrls.first()}"))

        mockMvc.post(
            path = Path("/manifest"),
            body = tagUrlsWrapper,
            headers = HttpHeaders().contentType()
        ) {
            responseJsonPath("$.items").isEmpty()
                .responseJsonPath("$.success").isFalse()
                .responseJsonPath("$.failure[0].url").equalsValue(tagUrlsWrapper.tagUrls.first())
                .responseJsonPath("$.failure[0].errorMessage")
                .equalsValue("repo url= malformed pattern=url:port/group/name:tag")
        }
    }

    @Test
    fun `Get tags given no authorization token throw ForbiddenException`() {
        val path = "/tags?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami"

        given(dockerService.getImageTags(any()))
            .willThrow(ForbiddenException("Authorization bearer token is not present"))

        mockMvc.get(Path(path)) {
            statusIsOk()
                .responseJsonPath("$.failure[0].errorMessage").equalsValue("Authorization bearer token is not present")
                .responseJsonPath("$.items").isEmpty()
                .responseJsonPath("$.success").isFalse()
        }
    }

    @Test
    fun `Get manifest given no authorization token throw ForbiddenException`() {
        val tagUrlsWrapper = TagUrlsWrapper(listOf("$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/2"))

        given(dockerService.getImageManifestInformation(any()))
            .willThrow(ForbiddenException("Authorization bearer token is not present"))

        mockMvc.post(
            path = Path("/manifest"),
            body = tagUrlsWrapper,
            headers = HttpHeaders().contentType()
        ) {
            statusIsOk()
                .responseJsonPath("$.failure[0].errorMessage").equalsValue("Authorization bearer token is not present")
                .responseJsonPath("$.items").isEmpty()
                .responseJsonPath("$.success").isFalse()
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/tags?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami",
            "/tags/semantic?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami"
        ]
    )
    fun `Get request given throw IllegalStateException`(path: String) {
        given(dockerService.getImageTags(any()))
            .willThrow(IllegalStateException("An error has occurred"))

        mockMvc.get(Path(path)) {
            responseJsonPath("$.failure[0].errorMessage").equalsValue("An error has occurred")
                .responseJsonPath("$.items").isEmpty()
                .responseJsonPath("$.success").isFalse()
        }
    }

    @Test
    fun `Post request given throw IllegalStateException`() {
        val tagUrlsWrapper = TagUrlsWrapper(listOf("$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/2"))

        given(dockerService.getImageManifestInformation(any()))
            .willThrow(IllegalStateException("An error has occurred"))

        mockMvc.post(
            path = Path("/manifest"),
            body = tagUrlsWrapper,
            headers = HttpHeaders().contentType()
        ) {
            responseJsonPath("$.failure[0].errorMessage").equalsValue("An error has occurred")
                .responseJsonPath("$.items").isEmpty()
                .responseJsonPath("$.success").isFalse()
        }
    }

    @Test
    fun `Verify groups tags correctly`() {
        val path = "/tags/semantic?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami"

        given(
            dockerService.getImageTags(any())
        ).willReturn(tags)

        mockMvc.get(Path(path)) {
            statusIsOk()
                .responseJsonPath("$.count").equalsValue(3)
                .responseJsonPath("$.success").equalsValue(true)
                .responseJsonPath("$.items[0].group").equalsValue("MAJOR")
                .responseJsonPath("$.items[0].tagResource[0].name").equalsValue("0")
                .responseJsonPath("$.items[0].itemsInGroup").equalsValue(1)
                .responseJsonPath("$.items[2].group").equalsValue("BUGFIX")
                .responseJsonPath("$.items[2].tagResource[0].name").equalsValue("0.0.0")
                .responseJsonPath("$.items[2].itemsInGroup").equalsValue(1)
        }
    }

    @Test
    fun `Verify that allowed override docker registry url is validated as allowed`() {
        val path = "/tags?repoUrl=allowedurl.no/no_skatteetaten_aurora_demo/whoami"

        given(dockerService.getImageTags(any()))
            .willReturn(tags)

        mockMvc.get(Path(path)) {
            statusIsOk()
                .responseJsonPath("$.success").isTrue()
        }
    }

    @Test
    fun `Verify that disallowed docker registry url returns bad request error`() {
        val repoUrl = "vg.no/no_skatteetaten_aurora_demo/whoami"
        val path = "/tags?repoUrl=$repoUrl"

        given(
            dockerService.getImageTags(any())
        ).willReturn(tags)

        mockMvc.get(Path(path)) {
            responseJsonPath("$.failure[0].errorMessage").equalsValue("Invalid Docker Registry URL url=vg.no")
                .responseJsonPath("$.failure[0].url").equalsValue(repoUrl)
                .responseJsonPath("$.success").isFalse()
        }
    }
}

private fun MockHttpServletRequestBuilder.setBody(tagUrls: List<String>): RequestBuilder =
    this.contentType(MediaType.APPLICATION_JSON_UTF8_VALUE)
        .content(jacksonObjectMapper().writeValueAsString(tagUrls))
