package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import no.skatteetaten.aurora.cantus.controller.BadRequestException
import no.skatteetaten.aurora.cantus.controller.ForbiddenException
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import no.skatteetaten.aurora.cantus.controller.blockAndHandleError
import no.skatteetaten.aurora.cantus.controller.handleError
import no.skatteetaten.aurora.cantus.controller.handleStatusCodeError
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration
import java.util.HashSet

private val logger = KotlinLogging.logger {}

@Service
class DockerRegistryService(
    val webClient: WebClient,
    val registryMetadataResolver: RegistryMetadataResolver,
    val imageRegistryUrlBuilder: ImageRegistryUrlBuilder
) {
    val dockerManfestAccept: List<MediaType> = listOf(
        MediaType.valueOf("application/vnd.docker.distribution.manifest.v2+json"),
        MediaType.valueOf("application/vnd.docker.distribution.manifest.v1+json")
    )

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
    val dockerContentDigestLabel = "Docker-Content-Digest"
    val createdLabel = "created"

    fun getImageManifestInformation(
        imageRepoCommand: ImageRepoCommand
    ): ImageManifestDto {
        val url = imageRepoCommand.registry

        if (imageRepoCommand.imageTag.isNullOrEmpty()) throw BadRequestException("Invalid url=${imageRepoCommand.fullRepoCommand}")

        val registryMetadata = registryMetadataResolver.getMetadataForRegistry(url)

        val dockerResponse = getManifestFromRegistry(imageRepoCommand, registryMetadata) { webClient ->
            webClient
                .get()
                .uri(
                    imageRegistryUrlBuilder.createManifestUrl(imageRepoCommand, registryMetadata),
                    imageRepoCommand.mappedTemplateVars
                )
                .headers {
                    it.accept = dockerManfestAccept
                }
        } ?: throw SourceSystemException(
            message = "Manifest not found for image ${imageRepoCommand.manifestRepo}",
            sourceSystem = url
        )

        return imageManifestResponseToImageManifest(
            imageRepoCommand = imageRepoCommand,
            imageManifestResponse = dockerResponse,
            imageRegistryMetadata = registryMetadata
        )
    }

    fun getImageTags(imageRepoCommand: ImageRepoCommand): ImageTagsWithTypeDto {
        val url = imageRepoCommand.registry

        val registryMetadata = registryMetadataResolver.getMetadataForRegistry(url)

        val tagsResponse: ImageTagsResponseDto? =
            getBodyFromDockerRegistry(imageRepoCommand, registryMetadata) { webClient ->
                logger.debug("Retrieving tags from $url")
                webClient
                    .get()
                    .uri(
                        imageRegistryUrlBuilder.createTagsUrl(imageRepoCommand, registryMetadata),
                        imageRepoCommand.mappedTemplateVars
                    )
            }

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

    private final inline fun <reified T : Any> getBodyFromDockerRegistry(
        imageRepoCommand: ImageRepoCommand,
        registryMetadata: RegistryMetadata,
        fn: (WebClient) -> WebClient.RequestHeadersSpec<*>
    ): T? = fn(webClient)
        .headers {
            if (registryMetadata.authenticationMethod == AuthenticationMethod.KUBERNETES_TOKEN) {
                it.setBearerAuth(
                    imageRepoCommand.bearerToken
                        ?: throw ForbiddenException("Authorization bearer token is not present")
                )
            }
        }
        .retrieve()
        .bodyToMono<T>()
        .blockAndHandleError(imageRepoCommand = imageRepoCommand)

    private fun getManifestFromRegistry(
        imageRepoCommand: ImageRepoCommand,
        registryMetadata: RegistryMetadata,
        fn: (WebClient) -> WebClient.RequestHeadersSpec<*>
    ): ImageManifestResponseDto? = fn(webClient)
        .headers {
            if (registryMetadata.authenticationMethod == AuthenticationMethod.KUBERNETES_TOKEN) {
                it.setBearerAuth(
                    imageRepoCommand.bearerToken
                        ?: throw ForbiddenException("Authorization bearer token is not present")
                )
            }
        }
        .exchange()
        .flatMap { resp ->
            resp.handleStatusCodeError(imageRepoCommand.registry)

            val dockerContentDigest = resp.headers().header(dockerContentDigestLabel).firstOrNull()
                ?: throw SourceSystemException(
                    message = "Response did not contain ${this.dockerContentDigestLabel} header",
                    sourceSystem = imageRepoCommand.registry
                )

            resp.bodyToMono<JsonNode>().map {
                val contentType = resp.headers().contentType().get().toString()
                ImageManifestResponseDto(contentType, dockerContentDigest, it)
            }
        }
        .handleError(imageRepoCommand)
        .block(Duration.ofSeconds(5))

    private fun imageManifestResponseToImageManifest(
        imageRepoCommand: ImageRepoCommand,
        imageManifestResponse: ImageManifestResponseDto,
        imageRegistryMetadata: RegistryMetadata
    ): ImageManifestDto {

        val manifestBody = imageManifestResponse
            .manifestBody
            .checkSchemaCompatibility(
                contentType = imageManifestResponse.contentType,
                imageRepoCommand = imageRepoCommand,
                imageRegistryMetadata = imageRegistryMetadata
            )

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

    private fun JsonNode.checkSchemaCompatibility(
        contentType: String,
        imageRepoCommand: ImageRepoCommand,
        imageRegistryMetadata: RegistryMetadata
    ): JsonNode =
        when (contentType) {
            "application/vnd.docker.distribution.manifest.v2+json" ->
                this.getV2Information(imageRepoCommand, imageRegistryMetadata)
            else -> {
                this.getV1CompatibilityFromManifest(imageRepoCommand)
            }
        }

    private fun JsonNode.getV2Information(
        imageRepoCommand: ImageRepoCommand,
        registryMetadata: RegistryMetadata
    ): JsonNode {
        val configDigest = listOf(
            this.at("/config").get("digest").asText().replace(
                regex = "\\s".toRegex(),
                replacement = ""
            ).split(":").last()
        ).associate { "configDigest" to it }

        return getBodyFromDockerRegistry(imageRepoCommand, registryMetadata) { webClient ->
            webClient
                .get()
                .uri(
                    imageRegistryUrlBuilder.createConfigUrl(
                        imageRepoCommand = imageRepoCommand,
                        registryMetadata = registryMetadata
                    ),
                    imageRepoCommand.mappedTemplateVars + configDigest
                )
                .headers {
                    it.accept = listOf(MediaType.valueOf("application/json"))
                }
        } ?: throw SourceSystemException(
            message = "Unable to retrieve V2 manifest for ${imageRepoCommand.defaultRepo}/sha256:$configDigest",
            sourceSystem = imageRepoCommand.registry
        )
    }

    private fun JsonNode.getV1CompatibilityFromManifest(imageRepoCommand: ImageRepoCommand): JsonNode {
        val v1Compatibility =
            this.get("history")?.get(0)?.get("v1Compatibility")?.asText() ?: throw SourceSystemException(
                message = "Body of v1 manifest is empty for image ${imageRepoCommand.manifestRepo}",
                sourceSystem = imageRepoCommand.registry
            )

        return jacksonObjectMapper().readTree(v1Compatibility)
    }

    private fun JsonNode.getVariableFromManifestBody(label: String) = this.get(label)?.asText() ?: ""
}

private fun JsonNode.getEnvironmentVariablesFromManifest() =
    this.at("/config/Env").associate {
        val (key, value) = it.asText().split("=")
        key to value
    }
