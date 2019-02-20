package no.skatteetaten.aurora.cantus.service

import mu.KotlinLogging
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@Component
class ImageRegistryUrlBuilder {
    fun createTagsUrl(
        imageRepoCommand: ImageRepoCommand,
        registryMetadata: RegistryMetadata
    ): String {
        logger.debug("Retrieving type=tags from  url=${registryMetadata.fullRegistryUrl} image=${imageRepoCommand.defaultRepo}")
        return "${registryMetadata.fullRegistryUrl}/{imageGroup}/{imageName}/tags/list"
    }

    fun createConfigUrl(
        imageRepoCommand: ImageRepoCommand,
        registryMetadata: RegistryMetadata
    ): String {
        logger.debug("Retrieving type=config from schemaVersion=v2 url=${registryMetadata.fullRegistryUrl} image=${imageRepoCommand.manifestRepo}")
        return "${registryMetadata.fullRegistryUrl}/{imageGroup}/{imageName}/blobs/sha256:{configDigest}"
    }

    fun createManifestUrl(
        imageRepoCommand: ImageRepoCommand,
        registryMetadata: RegistryMetadata
    ): String {
        logger.debug("Retrieving type=manifest from schemaVersion=v1 url=${registryMetadata.fullRegistryUrl} image=${imageRepoCommand.manifestRepo}")
        return "${registryMetadata.fullRegistryUrl}/{imageGroup}/{imageName}/manifests/{imageTag}"
    }
}