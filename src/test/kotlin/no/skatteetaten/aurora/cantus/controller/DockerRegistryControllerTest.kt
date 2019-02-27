package no.skatteetaten.aurora.cantus.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.nhaarman.mockito_kotlin.any
import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import no.skatteetaten.aurora.cantus.service.ImageManifestDto
import no.skatteetaten.aurora.cantus.service.ImageTagTypedDto
import no.skatteetaten.aurora.cantus.service.ImageTagsWithTypeDto
import no.skatteetaten.aurora.cantus.service.JavaImageDto
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

private const val defaultTestRegistry: String = "docker.com"

@WebMvcTest(
    value = [
        DockerRegistryController::class,
        ImageTagResourceAssembler::class,
        AuroraResponseAssembler::class,
        ImageRepoCommandAssembler::class
    ],
    secure = false
)
class DockerRegistryControllerTest {
    @MockBean
    private lateinit var dockerService: DockerRegistryService

    @Autowired
    private lateinit var mockMvc: MockMvc

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/manifest?tagUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/2",
            "/tags?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami",
            "/tags/semantic?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami"
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
            "/tags?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami",
            "/manifest?tagUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/2"
        ]
    )
    fun `Get docker registry image info given missing resource`(path: String) {

        val notFoundStatus = HttpStatus.NOT_FOUND
        val repoUrl = path.split("=")[1]

        given(dockerService.getImageTags(any())).willThrow(
            SourceSystemException(
                message = "Resource could not be found status=${notFoundStatus.value()} message=${notFoundStatus.reasonPhrase}",
                sourceSystem = "https://docker.com"
            )
        )

        given(dockerService.getImageManifestInformation(any())).willThrow(
            SourceSystemException(
                message = "Resource could not be found status=${notFoundStatus.value()} message=${notFoundStatus.reasonPhrase}",
                sourceSystem = "https://docker.com"
            )
        )

        mockMvc.perform(get(path))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.failure[0].url").value(repoUrl))
            .andExpect(jsonPath("$.failure[0].errorMessage").value("Resource could not be found status=${notFoundStatus.value()} message=${notFoundStatus.reasonPhrase}"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/tags?repoUrl=no_skatteetaten_aurora_demo/whaomi",
            "/manifest?tagUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami",
            "/tags/semantic?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora"
        ]
    )
    fun `Get request given invalid repoUrl and tagUrl throw BadRequestException`(path: String) {

        val repoUrl = path.split("=")[1]

        given(dockerService.getImageManifestInformation(any()))
            .willThrow(BadRequestException("Invalid url=$repoUrl"))

        mockMvc.perform(get(path))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.failure[0].url").value(repoUrl))
            .andExpect(jsonPath("$.failure[0].errorMessage").value("Invalid url=$repoUrl"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/tags?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami",
            "/manifest?tagUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/2"
        ]
    )
    fun `Get request given no authorization token throw ForbiddenException`(path: String) {
        given(dockerService.getImageTags(any()))
            .willThrow(ForbiddenException("Authorization bearer token is not present"))

        given(dockerService.getImageManifestInformation(any()))
            .willThrow(ForbiddenException("Authorization bearer token is not present"))

        mockMvc.perform(get(path))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.failure[0].errorMessage").value("Authorization bearer token is not present"))
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.success").value(false))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/tags?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami",
            "/tags/semantic?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami",
            "/manifest?tagUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami/2"
        ]
    )
    fun `Get request given throw IllegalStateException`(path: String) {
        given(dockerService.getImageTags(any()))
            .willThrow(IllegalStateException("An error has occurred"))

        given(dockerService.getImageManifestInformation(any()))
            .willThrow(IllegalStateException("An error has occurred"))

        mockMvc.perform(get(path))
            .andDo { print(it.response.contentAsString) }
            .andExpect(jsonPath("$.failure[0].errorMessage").value("An error has occurred"))
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `Verify groups tags correctly`() {
        val path = "/tags/semantic?repoUrl=$defaultTestRegistry/no_skatteetaten_aurora_demo/whoami"
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
        val path = "/tags?repoUrl=allowedurl.no/no_skatteetaten_aurora_demo/whoami"

        val tags = ImageTagsWithTypeDto(
            tags = parseJsonFromFile("dockerTagList.json")["tags"].map {
                ImageTagTypedDto(name = it.asText())
            }
        )
        given(dockerService.getImageTags(any()))
            .willReturn(tags)

        mockMvc.perform(get(path))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
    }

    @Test
    fun `Verify that disallowed docker registry url returns bad request error`() {
        val repoUrl = "vg.no/no_skatteetaten_aurora_demo/whoami"
        val path = "/tags?repoUrl=$repoUrl"

        val tags = ImageTagsWithTypeDto(
            tags = parseJsonFromFile("dockerTagList.json")["tags"].map {
                ImageTagTypedDto(name = it.asText())
            }
        )

        given(
            dockerService.getImageTags(any())
        ).willReturn(tags)

        mockMvc.perform(get(path))
            .andExpect(jsonPath("$.failure[0].errorMessage").value("Invalid Docker Registry URL url=vg.no"))
            .andExpect(jsonPath("$.failure[0].url").value(repoUrl))
            .andExpect(jsonPath("$.success").value(false))
    }

    fun parseJsonFromFile(fileName: String): JsonNode {
        val classPath = ClassPathResource("/$fileName")
        return jacksonObjectMapper().readTree(classPath.file)
    }
}
