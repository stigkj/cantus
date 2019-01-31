package no.skatteetaten.aurora.cantus.service

import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand

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
        logger.debug("Retrieving type=config from schemaVersion=v2 url=${registryMetadata.fullRegistryUrl} image=${imageRepoCommand.manifestRepo}")
        return "${registryMetadata.fullRegistryUrl}/{imageGroup}/{imageName}/manifests/{imageTag}"
    }
}