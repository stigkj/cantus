package no.skatteetaten.aurora.cantus.controller

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import kotlinx.coroutines.newFixedThreadPoolContext
import no.skatteetaten.aurora.cantus.AuroraIntegration
import no.skatteetaten.aurora.cantus.ImageManifestDtoBuilder
import no.skatteetaten.aurora.cantus.ImageTagsWithTypeDtoBuilder
import no.skatteetaten.aurora.cantus.createObjectMapper
import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import no.skatteetaten.aurora.mockmvc.extensions.Path
import no.skatteetaten.aurora.mockmvc.extensions.TestObjectMapperConfigurer
import no.skatteetaten.aurora.mockmvc.extensions.contentTypeJson
import no.skatteetaten.aurora.mockmvc.extensions.get
import no.skatteetaten.aurora.mockmvc.extensions.post
import no.skatteetaten.aurora.mockmvc.extensions.responseJsonPath
import no.skatteetaten.aurora.mockmvc.extensions.statusIsOk
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc

private const val defaultTestRegistry: String = "docker.com"

@AutoConfigureRestDocs
@WebMvcTest(
    value = [
        DockerRegistryController::class,
        AuroraResponseAssembler::class,
        ImageTagResourceAssembler::class,
        ImageRepoCommandAssembler::class,
        AuroraIntegration::class,
        ImageBuildTimeline::class
    ]
)
class DockerRegistryControllerContractTest {

    @TestConfiguration
    @EnableConfigurationProperties(AuroraIntegration::class)
    class DockerRegistryControllerContractTestConfiguration {
        @Bean
        fun threadPoolContext() = newFixedThreadPoolContext(2, "cantus")
    }

    @MockkBean
    private lateinit var dockerService: DockerRegistryService

    @Autowired
    private lateinit var mockMvc: MockMvc

    init {
        TestObjectMapperConfigurer.objectMapper = createObjectMapper()
    }

    @AfterAll
    fun tearDown() {
        TestObjectMapperConfigurer.reset()
    }

    @Test
    fun `Get docker registry image manifest with POST  and one malformed url return Partial Success`() {
        val manifest = ImageManifestDtoBuilder().build()
        val tagUrlsWrapper = TagUrlsWrapper(
            listOf(
                "$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/2",
                "$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/1"
            )
        )

        every {
            dockerService.getImageManifestInformation(any())
        } answers {
            val imageRepoCommand = firstArg<ImageRepoCommand>()
            if (imageRepoCommand.imageTag == "1") throw SourceSystemException("Docker api not responding")
            else manifest
        }

        mockMvc.post(
            Path("/manifest"),
            headers = HttpHeaders().contentTypeJson(),
            body = createObjectMapper().writeValueAsString(tagUrlsWrapper)
        ) {
            statusIsOk()
                .responseJsonPath("$.success").equalsValue(false)
                .responseJsonPath("$.items").isNotEmpty()
                .responseJsonPath("$.failureCount").equalsValue(1)
                .responseJsonPath("$.successCount").equalsValue(1)
        }
    }

    @Test
    fun `Get docker registry image tags with GET`() {

        every {
            dockerService.getImageTags(any(), any())
        } returns ImageTagsWithTypeDtoBuilder(
            tags = listOf(
                "dev-SNAPSHOT",
                "SNAPSHOT--dev-20170912.120730-1-b1.4.1-wingnut-8.141.1",
                "latest",
                "1",
                "1.0.0",
                "1.1.0-b1.4.1-wingnut-8.141.1",
                "1.2"
            )
        ).build()

        mockMvc.get(Path("/tags?repoUrl={registryUrl}/{namespace}/{name}", defaultTestRegistry, "namespace", "name")) {
            statusIsOk()
                .responseJsonPath("$.successCount").equalsValue(7)
        }
    }
}
