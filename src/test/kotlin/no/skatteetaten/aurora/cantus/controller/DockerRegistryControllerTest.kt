package no.skatteetaten.aurora.cantus.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.anyOrNull
import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import no.skatteetaten.aurora.cantus.service.ImageManifestDto
import no.skatteetaten.aurora.cantus.service.ImageTagTypedDto
import no.skatteetaten.aurora.cantus.service.ImageTagsWithTypeDto
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
    value = [DockerRegistryController::class, ErrorHandler::class, ImageTagResourceAssembler::class],
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
            ImageManifestDto(auroraVersion = "2", dockerVersion = "2", dockerDigest = "sah", appVersion = "2")

        given(dockerService.getImageManifestInformation(any(), any(), any(), anyOrNull())).willReturn(manifest)
        given(dockerService.getImageTags(any(), any(), anyOrNull())).willReturn(tags)

        given(
            dockerService.getImageTags(
                any(),
                any(),
                anyOrNull()
            )
        ).willReturn(tags)
        mockMvc.perform(get(path))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$").isNotEmpty)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/no_skatteetaten/test/tags",
            "/no_skatteetaten/test/0/manifest",
            "/no_skatteetaten/test/0/manifest"
        ]
    )
    fun `Get docker registry image info given missing resource`(path: String) {
        val dockerUrl = "https://localhost.no"

        given(dockerService.getImageTags(any(), any(), anyOrNull())).willThrow(
            SourceSystemException(
                message = "Tags not found for image no_skatteetaten/test",
                code = "404",
                sourceSystem = "https://docker"
            )
        )

        given(dockerService.getImageManifestInformation(any(), any(), any(), anyOrNull())).willThrow(
            SourceSystemException(
                message = "Manifest not found for image no_skatteetaten/test:0",
                code = "404",
                sourceSystem = "https://docker"
            )
        )

        given(dockerService.getImageManifestInformation(any(), any(), any(), anyOrNull())).willThrow(
            SourceSystemException(
                message = "Unable to retrieve V2 manifest from https:/docker/v2/no_skatteetaten/test/blobs/sha256:2456",
                code = "404",
                sourceSystem = "https://docker"
            )
        )

        mockMvc.perform(get(path))
            .andExpect(status().`is`(500))
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.exception.code").value("404"))
            .andExpect(jsonPath("$.exception.sourceSystem").value("https://docker"))
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
            dockerService.getImageTags(
                any(),
                any(),
                anyOrNull()
            )
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

    fun parseJsonFromFile(fileName: String): JsonNode {
        val classPath = ClassPathResource("/$fileName")
        return jacksonObjectMapper().readTree(classPath.file)
    }
}