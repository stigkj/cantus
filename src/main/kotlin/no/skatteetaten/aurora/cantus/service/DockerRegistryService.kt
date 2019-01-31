package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.cantus.controller.BadRequestException
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import no.skatteetaten.aurora.cantus.controller.blockAndHandleError
import no.skatteetaten.aurora.cantus.controller.blockTimeout
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration
import java.util.HashSet

val logger = LoggerFactory.getLogger(DockerRegistryService::class.java)

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
        "NODE_VERSION"
    )

    val dockerVersionLabel = "docker_version"
    val dockerContentDigestLabel = "Docker-Content-Digest"
    val createdLabel = "created"

    fun getImageManifestInformation(
        imageRepoCommand: ImageRepoCommand
    ): ImageManifestDto {
        val url = imageRepoCommand.registry

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
            code = "404",
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
                message = "Tags not found for image ${imageRepoCommand.defaultRepo}",
                code = "404",
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
                        ?: throw BadRequestException(message = "Authorization bearer token is not present")
                )
            }
        }
        .retrieve()
        .bodyToMono<T>()
        .blockAndHandleError(sourceSystem = imageRepoCommand.registry)

    private fun getManifestFromRegistry(
        imageRepoCommand: ImageRepoCommand,
        registryMetadata: RegistryMetadata,
        fn: (WebClient) -> WebClient.RequestHeadersSpec<*>
    ): ImageManifestResponseDto? = fn(webClient)
        .headers {
            if (registryMetadata.authenticationMethod == AuthenticationMethod.KUBERNETES_TOKEN) {
                it.setBearerAuth(
                    imageRepoCommand.bearerToken
                        ?: throw BadRequestException(message = "Authorization bearer token is not present")
                )
            }
        }
        .exchange()
        .flatMap { resp ->

            val dockerContentDigest = resp.headers().header(dockerContentDigestLabel).firstOrNull()
            if (resp.statusCode().is2xxSuccessful && dockerContentDigest != null) {
                resp.bodyToMono<JsonNode>().map {
                    val contentType = resp.headers().contentType().get().toString()
                    ImageManifestResponseDto(contentType, dockerContentDigest, it)
                }
            } else {
                resp.bodyToMono<String>().map {
                    val message = if (dockerContentDigest == null) {
                        // TODO: Should this be an empty mono?
                        "No docker content digest label present on response"
                    } else {
                        "Error in response, status=${resp.rawStatusCode()} body=$it"
                    }
                    throw SourceSystemException(
                        message = message,
                        sourceSystem = imageRepoCommand.registry,
                        code = resp.statusCode().name
                    )
                }
            }
        }.block(Duration.ofSeconds(blockTimeout))

    private fun imageManifestResponseToImageManifest(
        imageRepoCommand: ImageRepoCommand,
        imageManifestResponse: ImageManifestResponseDto,
        imageRegistryMetadata: RegistryMetadata
    ): ImageManifestDto {

        val manifestBody = imageManifestResponse
            .manifestBody.checkSchemaCompatibility(
            contentType = imageManifestResponse.contentType,
            imageRepoCommand = imageRepoCommand,
            imageRegistryMetadata = imageRegistryMetadata
        )

        val environmentVariables = manifestBody.getEnvironmentVariablesFromManifest()

        val imageManifestEnvInformation = environmentVariables
            .filter { manifestEnvLabels.contains(it.key) }
            .mapKeys { it.key.toUpperCase() }

        val dockerVersion = manifestBody.getVariableFromManifestBody(dockerVersionLabel)
        val created = manifestBody.getVariableFromManifestBody(createdLabel)

        return ImageManifestDto(
            dockerVersion = dockerVersion,
            dockerDigest = imageManifestResponse.dockerContentDigest,
            buildEnded = created,
            auroraVersion = imageManifestEnvInformation["AURORA_VERSION"],
            nodeVersion = imageManifestEnvInformation["NODE_VERSION"],
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
                this.getV1CompatibilityFromManifest()
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
            code = "404",
            sourceSystem = imageRepoCommand.registry
        )
    }

    private fun JsonNode.getV1CompatibilityFromManifest() =
        jacksonObjectMapper().readTree(this.get("history")?.get(0)?.get("v1Compatibility")?.asText() ?: "")
            ?: NullNode.instance

    private fun JsonNode.getVariableFromManifestBody(label: String) = this.get(label)?.asText() ?: ""
}

private fun JsonNode.getEnvironmentVariablesFromManifest() =
    this.at("/config/Env").associate {
        val (key, value) = it.asText().split("=")
        key to value
    }