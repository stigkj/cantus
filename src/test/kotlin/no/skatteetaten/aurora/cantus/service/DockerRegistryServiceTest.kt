package no.skatteetaten.aurora.cantus.service

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.message
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.newFixedThreadPoolContext
import no.skatteetaten.aurora.cantus.AuroraIntegration.AuthType.Bearer
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import org.junit.jupiter.api.Test
import org.springframework.util.ResourceUtils
import reactor.core.publisher.Mono
import reactor.core.publisher.toMono

class DockerRegistryServiceTest {

    private val from = ImageRepoCommand(
        registry = "test.com:5000}",
        imageGroup = "no_skatteetaten_aurora_demo",
        imageName = "whoami",
        imageTag = "2",
        token = "bearer token",
        authType = Bearer,
        url = "http://test.com:5000/v2"
    )

    private val to = from.copy(registry = "test2.com:5000")

    private val objectMapper = jacksonObjectMapper()

    private val dtoV2 = ImageManifestResponseDto(
        contentType = manifestV2,
        dockerContentDigest = "sha256",
        manifestBody = objectMapper.readTestResourceAsJson("dockerManifestV2.json")
    )
    private val httpClient = mockk<DockerHttpClient>()

    private val dockerService = DockerRegistryService(
        httpClient,
        newFixedThreadPoolContext(6, "cantus")
    )

    @Test
    fun `Verify that if V2 content type is set then retrieve manifest with V2 method`() {
        every {
            httpClient.getImageManifest(any())
        } returns dtoV2

        every {
            httpClient.getConfig(
                any(),
                any()
            )
        } returns objectMapper.readTestResourceAsJson("dockerManifestV2Config.json")

        val jsonResponse = dockerService.getImageManifestInformation(from)

        assertThat(jsonResponse).isNotNull().given {
            assertThat(it.dockerDigest).isEqualTo("sha256")
            assertThat(it.nodeVersion).isEqualTo(null)
            assertThat(it.buildEnded).isEqualTo("2018-11-05T14:01:22.654389192Z")
            assertThat(it.java?.major).isEqualTo("8")
        }
    }

    @Test
    fun `Verify that V2 manifest not found is handled`() {

        every {
            httpClient.getImageManifest(any())
        } returns dtoV2

        every {
            httpClient.getConfig(
                any(),
                any()
            )
        } throws SourceSystemException("Unable to retrieve V2 manifest", sourceSystem = "registry")

        assertThat {
            dockerService.getImageManifestInformation(from)
        }.isFailure().isNotNull().isInstanceOf(SourceSystemException::class)
            .message().isNotNull().contains("Unable to retrieve V2 manifest")
    }

    @Test
    fun `Verify fetches all tags for specified image`() {

        every { httpClient.getImageTags(any()) } returns ImageTagsResponseDto(
            listOf(
                "0", "0.0", "0.0.0", "0.0.0-b1.17.0-flange-8.181.1", "latest"
            )
        )

        val tags = dockerService.getImageTags(from)

        assertThat(tags).isNotNull().given {
            assertThat(it.tags.size).isEqualTo(5)
            assertThat(it.tags[0].name).isEqualTo("0")
            assertThat(it.tags[1].name).isEqualTo("0.0")
            assertThat(it.tags[2].name).isEqualTo("0.0.0")
        }
    }

    @Test
    fun `Verify fetches all tags for specified image and filter them`() {

        every { httpClient.getImageTags(any()) } returns ImageTagsResponseDto(
            listOf(
                "0", "0.0", "0.0.0", "0.0.0-b1.17.0-flange-8.181.1", "latest"
            )
        )

        val tags = dockerService.getImageTags(from, "flange")

        assertThat(tags).isNotNull().given {
            assertThat(it.tags.size).isEqualTo(1)
            assertThat(it.tags[0].name).isEqualTo("0.0.0-b1.17.0-flange-8.181.1")
        }
    }

    @Test
    fun `Verify that empty tag list throws SourceSystemException`() {
        every { httpClient.getImageTags(any()) } returns ImageTagsResponseDto(
            emptyList()
        )

        assertThat { dockerService.getImageTags(from) }
            .isFailure()
            .isNotNull().isInstanceOf(SourceSystemException::class)
            .message().isNotNull().contains("status=404 message=Not Found")
    }

    @Test
    fun `should find blobs for v2`() {
        val layers = dockerService.findBlobs(dtoV2)
        assertThat(layers.size).isEqualTo(4)
    }

    fun ObjectMapper.readTestResourceAsJson(fileName: String): JsonNode {
        return this.readValue(ResourceUtils.getURL("src/test/resources/$fileName"))
    }

    @Test
    fun `should tag from one repo to another when all digest exist`() {

        every {
            httpClient.getImageManifest(any())
        } returns dtoV2

        every { httpClient.digestExistInRepo(any(), any()) } returns true

        every { httpClient.putManifest(any(), any()) } returns true

        val result = dockerService.tagImage(
            from = from, to = to
        )

        assertThat(result).isTrue()
    }

    @Test
    fun `should put non existing blob`() {
        val digest = "sha256::foobar"
        val uuid = "uuid-is-this"

        val blob: Mono<ByteArray> = "this is teh content".toByteArray().toMono()

        every { httpClient.digestExistInRepo(to, digest) } returns false

        every { httpClient.getUploadUUID(to) } returns uuid

        every { httpClient.getLayer(from, digest) } returns blob

        every { httpClient.uploadLayer(to, uuid, digest, blob) } returns true

        val result = dockerService.ensureBlobExist(from, to, digest)
        assertThat(result).isTrue()
    }
}
