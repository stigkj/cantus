package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import mu.KotlinLogging
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.util.HashSet

private val logger = KotlinLogging.logger {}

@Service
class DockerRegistryService(
    val httpClient: DockerHttpClient,
    val threadPoolContext: ExecutorCoroutineDispatcher
) {

    val manifestEnvLabels: HashSet<String> = hashSetOf(
        "AURORA_VERSION",
        "IMAGE_BUILD_TIME",
        "APP_VERSION",
        "JOLOKIA_VERSION",
        "JAVA_VERSION_MAJOR",
        "JAVA_VERSION_MINOR",
        "JAVA_VERSION_BUILD",
        "NODEJS_VERSION"
    )

    val dockerVersionLabel = "docker_version"
    val createdLabel = "created"

    /*
       Inspiration from method in the following blogpost https://www.danlorenc.com/posts/containers-part-2/
     */
    fun tagImage(from: ImageRepoCommand, to: ImageRepoCommand): Boolean {

        val manifest = httpClient.getImageManifest(from)
        val layers = findBlobs(manifest)

        runBlocking(threadPoolContext + MDCContext()) {
            layers.map { digest ->
                async {
                    ensureBlobExist(from, to, digest)
                }
            }.forEach { it.await() }
        }
        return httpClient.putManifest(to, manifest).also {
            logger.info("Tagged docker image from=${from.fullRepoCommand} to=${to.fullRepoCommand} manifest=$manifest")
        }
    }

    fun ensureBlobExist(from: ImageRepoCommand, to: ImageRepoCommand, digest: String): Boolean {

        if (httpClient.digestExistInRepo(to, digest)) {
            logger.debug("layer=$digest already exist in registry=${to.artifactRepo}")
            return true
        }

        val uuid = httpClient.getUploadUUID(to)
        val data: Mono<ByteArray> = httpClient.getLayer(from, digest)

        return httpClient.uploadLayer(to, uuid, digest, data).also {
            logger.debug("Blob=$digest pushed to=${to.artifactRepo} success=$it")
        }
    }

    private fun imageManifestResponseToImageManifest(
        imageRepoCommand: ImageRepoCommand,
        imageManifestResponse: ImageManifestResponseDto
    ): ImageManifestDto {

        val configDigest = imageManifestResponse.manifestBody.at("/config/digest").textValue()

        val manifestBody = httpClient.getConfig(imageRepoCommand, configDigest)

        val environmentVariables = manifestBody.getEnvironmentVariablesFromManifest()

        val imageManifestEnvInformation = environmentVariables
            .mapKeys { it.key.toUpperCase() }
            .filter { manifestEnvLabels.contains(it.key) }

        val dockerVersion = manifestBody.getVariableFromManifestBody(dockerVersionLabel)
        val created = manifestBody.getVariableFromManifestBody(createdLabel)

        return ImageManifestDto(
            dockerVersion = dockerVersion,
            dockerDigest = imageManifestResponse.dockerContentDigest,
            buildEnded = created,
            auroraVersion = imageManifestEnvInformation["AURORA_VERSION"],
            nodeVersion = imageManifestEnvInformation["NODEJS_VERSION"],
            appVersion = imageManifestEnvInformation["APP_VERSION"],
            buildStarted = imageManifestEnvInformation["IMAGE_BUILD_TIME"],
            java = JavaImageDto.fromEnvMap(imageManifestEnvInformation),
            jolokiaVersion = imageManifestEnvInformation["JOLOKIA_VERSION"]
        )
    }
    private fun JsonNode.getVariableFromManifestBody(label: String) = this.get(label)?.asText() ?: ""

    fun findBlobs(manifest: ImageManifestResponseDto): List<String> {
        val layers: ArrayNode = manifest.manifestBody["layers"] as ArrayNode
        return layers.map { it["digest"].textValue() } + manifest.manifestBody.at("/config/digest").textValue()
    }

    fun getImageManifestInformation(
        imageRepoCommand: ImageRepoCommand
    ): ImageManifestDto {
        val dockerResponse = httpClient.getImageManifest(imageRepoCommand)

        return imageManifestResponseToImageManifest(
            imageRepoCommand = imageRepoCommand,
            imageManifestResponse = dockerResponse
        )
    }

    fun getImageTags(imageRepoCommand: ImageRepoCommand): ImageTagsWithTypeDto {

        val url = imageRepoCommand.registry
        val tagsResponse = httpClient.getImageTags(imageRepoCommand)

        if (tagsResponse == null || tagsResponse.tags.isEmpty()) {
            throw SourceSystemException(
                message = "Resource could not be found status=${HttpStatus.NOT_FOUND.value()} message=${HttpStatus.NOT_FOUND.reasonPhrase}",
                sourceSystem = url
            )
        }

        return ImageTagsWithTypeDto(tags = tagsResponse.tags.map {
            ImageTagTypedDto(it)
        })
    }
}

private fun JsonNode.getEnvironmentVariablesFromManifest() =
    this.at("/config/Env").associate {
        val (key, value) = it.asText().split("=")
        key to value
    }
