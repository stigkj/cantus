package no.skatteetaten.aurora.cantus.controller

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.reset
import com.nhaarman.mockito_kotlin.times
import com.nhaarman.mockito_kotlin.verify
import kotlinx.coroutines.newFixedThreadPoolContext
import no.skatteetaten.aurora.cantus.ImageManifestDtoBuilder
import no.skatteetaten.aurora.cantus.ImageTagsWithTypeDtoBuilder
import no.skatteetaten.aurora.cantus.createObjectMapper
import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.TestObjectMapperConfigurer
import no.skatteetaten.aurora.mockmvc.extensions.contentType
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.mock.withContractResponse
import no.skatteetaten.aurora.mockmvc.extensions.post
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.MockMvc

private const val defaultTestRegistry: String = "docker.com"

@AutoConfigureRestDocs
@WebMvcTest(
    value = [
        DockerRegistryController::class,
        AuroraResponseAssembler::class,
        ImageTagResourceAssembler::class,
        ImageRepoCommandAssembler::class
    ],
    secure = false
)
class DockerRegistryControllerContractTest {

    @TestConfiguration
    class DockerRegistryControllerContractTestConfiguration {
        @Bean
        fun threadPoolContext() = newFixedThreadPoolContext(2, "cantus")
    }

    @MockBean
    private lateinit var dockerService: DockerRegistryService

    @Autowired
    private lateinit var mockMvc: MockMvc

    private val tags = ImageTagsWithTypeDtoBuilder("no_skatteetaten_aurora_demo", "whoami").build()

    @MockBean
    private lateinit var mockedImageTagResourceAssembler: ImageTagResourceAssembler

    init {
        TestObjectMapperConfigurer.objectMapper = createObjectMapper()
    }

    @AfterAll
    fun tearDown() {
        TestObjectMapperConfigurer.reset()
    }

    @BeforeEach
    fun reset() {
        reset(mockedImageTagResourceAssembler)
    }

    @Test
    fun `Get docker registry image manifest with POST`() {
        val manifest = ImageManifestDtoBuilder().build()
        val tagUrl = listOf(
            "$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/2",
            "$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/1"
        )

        given(dockerService.getImageManifestInformation(any())).willReturn(manifest)

        val imageTagResource =
            given(mockedImageTagResourceAssembler.imageTagResourceToAuroraResponse(any()))
                .withContractResponse("imagetagresource/partialSuccess") { willReturn(content) }.mockResponse

        mockMvc.post(
            path = Path("/manifest"),
            headers = HttpHeaders().contentType(),
            body = tagUrl
        ) {
            statusIsOk()
                .responseJsonPath("$").equalsObject(imageTagResource)
                .responseJsonPath("$.success").equalsValue(false)
        }

        verify(dockerService, times(2)).getImageManifestInformation(any())
    }

    @Test
    fun `Get docker registry image tags with GET`() {
        val path = "/tags?repoUrl=url/namespace/name"
        given(dockerService.getImageTags(any())).willReturn(tags)

        val tagResource = given(mockedImageTagResourceAssembler.tagResourceToAuroraResponse(any()))
            .withContractResponse("tagresource/TagResource") {
                willReturn(content)
            }.mockResponse

        mockMvc.get(Path(path)) {
            statusIsOk()
                .responseJsonPath("$").equalsObject(tagResource)
        }
    }

    @Test
    fun `Get docker registry image tags grouped with GET`() {
        val path = "/tags/semantic?repoUrl=url/namespace/name"
        given(dockerService.getImageTags(any())).willReturn(tags)

        val groupedTagResource =
            given(mockedImageTagResourceAssembler.groupedTagResourceToAuroraResponse(any()))
                .withContractResponse("tagresource/GroupedTagResource") {
                    willReturn(content)
                }.mockResponse

        mockMvc.get(Path(path)) {
            statusIsOk()
                .responseJsonPath("$").equalsObject(groupedTagResource)
        }
    }

    @Test
    fun `Get docker registry image tags with GET given missing resource`() {
        val path = "/tags?repoUrl=url/namespace/missing"
        val notFoundStatus = HttpStatus.NOT_FOUND

        given(dockerService.getImageTags(any())).willThrow(
            SourceSystemException(
                message = "Resource could not be found status=${notFoundStatus.value()} message=${notFoundStatus.reasonPhrase}",
                sourceSystem = "https://docker.com"
            )
        )

        val tagResourceNotFound = given(mockedImageTagResourceAssembler.tagResourceToAuroraResponse(any()))
            .withContractResponse("tagresource/TagResourceNotFound") {
                willReturn(content)
            }.mockResponse

        mockMvc.get(Path(path)) {
            statusIsOk()
                .responseJsonPath("$").equalsObject(tagResourceNotFound)
        }
    }
}