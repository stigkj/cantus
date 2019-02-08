package no.skatteetaten.aurora.cantus.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nhaarman.mockito_kotlin.any
import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import no.skatteetaten.aurora.cantus.service.ImageManifestDto
import no.skatteetaten.aurora.cantus.service.ImageTagTypedDto
import no.skatteetaten.aurora.cantus.service.ImageTagsWithTypeDto
import no.skatteetaten.aurora.cantus.service.JavaImageDto
import org.junit.Before
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.core.io.ClassPathResource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

@WebMvcTest(
    value = [DockerRegistryController::class, ErrorHandler::class, ImageTagResourceAssembler::class, ImageRepoDtoAssembler::class],
    secure = false
)
class DockerRegistryControllerTest {
    @MockBean
    private lateinit var dockerService: DockerRegistryService

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Before
    fun setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(dockerService).setControllerAdvice(ErrorHandler()).build()
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/no_skatteetaten_aurora_demo/whoami/2/manifest",
            "/no_skatteetaten_aurora_demo/whoami/tags",
            "/no_skatteetaten_aurora_demo/whoami/tags/semantic"
        ]
    )
    fun `Get docker registry image info`(path: String) {
        val tags = ImageTagsWithTypeDto(tags = listOf(ImageTagTypedDto("test")))
        val manifest =
            ImageManifestDto(
                auroraVersion = "2",
                dockerVersion = "2",
                dockerDigest = "sah",
                appVersion = "2",
                nodeVersion = "2",
                java = JavaImageDto(
                    major = "2",
                    minor = "0",
                    build = "0"
                )
            )

        given(dockerService.getImageManifestInformation(any())).willReturn(manifest)
        given(dockerService.getImageTags(any())).willReturn(tags)

        given(
            dockerService.getImageTags(any())
        ).willReturn(tags)
        mockMvc.perform(get(path))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isNotEmpty)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/no_skatteetaten_aurora_demo/whoami/tags",
            "/no_skatteetaten_aurora_demo/whoami/2/manifest"
        ]
    )
    fun `Get docker registry image info given missing resource`(path: String) {

        given(dockerService.getImageTags(any())).willThrow(
            SourceSystemException(
                message = "Tags not found for image no_skatteetaten/test",
                sourceSystem = "https://docker.com"
            )
        )

        given(dockerService.getImageManifestInformation(any())).willThrow(
            SourceSystemException(
                message = "Manifest not found for image no_skatteetaten/test:0",
                sourceSystem = "https://docker.com"
            )
        )

        mockMvc.perform(get(path))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.exception.sourceSystem").value("https://docker.com"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/no_skatteetaten_aurora_demo/whoami/tags",
            "/no_skatteetaten_aurora_demo/whoami/2/manifest"
        ]
    )
    fun `Get request given no authorization token throw ForbiddenException`(path: String) {
        given(dockerService.getImageTags(any())).willThrow(
            ForbiddenException("Authorization bearer token is not present")
        )

        given(dockerService.getImageManifestInformation(any())).willThrow(
            ForbiddenException("Authorization bearer token is not present")
        )

        mockMvc.perform(get(path))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.success").value(false))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/no_skatteetaten_aurora_demo/whoami/tags",
            "/no_skatteetaten_aurora_demo/whoami/tags/semantic",
            "/no_skatteetaten_aurora_demo/whoami/2/manifest"
        ]
    )
    fun `Get request given throw IllegalStateException`(path: String) {
        given(dockerService.getImageTags(any())).willThrow(
            IllegalStateException("An error has occurred")
        )

        given(dockerService.getImageManifestInformation(any())).willThrow(
            IllegalStateException("An error has occurred")
        )

        mockMvc.perform(get(path))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `Verify groups tags correctly`() {
        val path = "/no_skatteetaten_aurora_demo/whoami/tags/semantic"
        val tags = ImageTagsWithTypeDto(
            tags = parseJsonFromFile("dockerTagList.json")["tags"].map {
                ImageTagTypedDto(name = it.asText())
            }
        )
        given(
            dockerService.getImageTags(any())
        ).willReturn(tags)

        mockMvc.perform(get(path))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.count").value(4))
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.items[0].group").value("MAJOR"))
            .andExpect(jsonPath("$.items[0].tagResource[0].name").value("0"))
            .andExpect(jsonPath("$.items[0].itemsInGroup").value(1))
            .andExpect(jsonPath("$.items[2].group").value("BUGFIX"))
            .andExpect(jsonPath("$.items[2].tagResource[0].name").value("0.0.0"))
            .andExpect(jsonPath("$.items[2].itemsInGroup").value(2))
    }

    @Test
    fun `Verify that allowed override docker registry url is validated as allowed`() {
        val path = "/no_skatteetaten_aurora_demo/whoami/tags?dockerRegistryUrl=allowedurl.no"

        val tags = ImageTagsWithTypeDto(
            tags = parseJsonFromFile("dockerTagList.json")["tags"].map {
                ImageTagTypedDto(name = it.asText())
            }
        )
        given(
            dockerService.getImageTags(any())
        ).willReturn(tags)

        mockMvc.perform(get(path))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
    }

    @Test
    fun `Verify that disallowed docker registry url returns bad request error`() {
        val path = "/no_skatteetaten_aurora_demo/whoami/tags?dockerRegistryUrl=vg.no"

        val tags = ImageTagsWithTypeDto(
            tags = parseJsonFromFile("dockerTagList.json")["tags"].map {
                ImageTagTypedDto(name = it.asText())
            }
        )
        given(
            dockerService.getImageTags(any())
        ).willReturn(tags)

        mockMvc.perform(get(path))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
    }

    fun parseJsonFromFile(fileName: String): JsonNode {
        val classPath = ClassPathResource("/$fileName")
        return jacksonObjectMapper().readTree(classPath.file)
    }
}
