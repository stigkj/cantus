package no.skatteetaten.aurora.cantus.controller

import no.skatteetaten.aurora.cantus.AuroraIntegration
import org.springframework.stereotype.Component

data class TagCommand(
    val from: String,
    val to: String
    // TODO: We could here add an additionalTags string with csv of aditional tags for ease of use.
    // TODO: The only thing you need to do then is push the manifest several times
)

data class ImageRepoCommand(
    val url: String,
    val registry: String,
    val imageGroup: String,
    val imageName: String,
    val imageTag: String? = null,
    val token: String? = null,
    val authType: AuroraIntegration.AuthType
) {
    val manifestRepo: String
        get() = listOf(imageGroup, imageName, imageTag).joinToString("/")
    val defaultRepo: String
        get() = listOf(imageGroup, imageName).joinToString("/")
    val artifactRepo: String
        get() = listOf(registry, imageGroup, imageName).joinToString("/")
    val fullRepoCommand: String
        get() = listOf(registry, imageGroup, imageName, imageTag).joinToString("/")
    val mappedTemplateVars: Map<String, String?>
        get() = mapOf(
            "imageGroup" to imageGroup,
            "imageName" to imageName,
            "imageTag" to imageTag
        )
}

data class ImageRepo(
    val registry: String,
    val imageGroup: String,
    val imageName: String,
    val imageTag: String?
)

fun AuroraIntegration.findRegistry(registry: String): AuroraIntegration.DockerRegistry? =
    this.docker.values.find { it.url == registry && it.isEnabled }

@Component
class ImageRepoCommandAssembler(
    val aurora: AuroraIntegration
) {
    fun createAndValidateCommand(
        url: String,
        bearerToken: String? = null
    ): ImageRepoCommand {
        val imageRepo = url.toImageRepo()

        val registry = aurora.findRegistry(imageRepo.registry)
            ?: throw BadRequestException("Invalid Docker Registry URL url=${imageRepo.registry}")

        if (registry.auth == null)
            throw BadRequestException("Registry authType is required")

        if (registry.auth != AuroraIntegration.AuthType.None && bearerToken.isNullOrBlank())
            throw BadRequestException("Registry required authentication")

        val scheme = if (registry.isHttps) "https://" else "http://"

        return ImageRepoCommand(
            registry = imageRepo.registry,
            imageName = imageRepo.imageName,
            imageGroup = imageRepo.imageGroup,
            imageTag = imageRepo.imageTag,
            authType = registry.auth,
            token = bearerToken?.split(" ")?.last(),
            url = "$scheme${imageRepo.registry}/v2"
        )
    }

    private fun String.toImageRepo(): ImageRepo {
        val repoVariables = this.split("/")
        val repoVariablesSize = repoVariables.size

        if (repoVariablesSize < 3 || repoVariablesSize > 4) throw IllegalArgumentException("repo url=$this malformed pattern=url:port/group/name:tag")

        if (repoVariablesSize == 3 && repoVariables[2].contains(":")) {
            val (name, tag) = repoVariables[2].split(":")
            return ImageRepo(
                registry = repoVariables[0],
                imageGroup = repoVariables[1],
                imageName = name,
                imageTag = tag
            )
        }

        return ImageRepo(
            registry = repoVariables[0],
            imageGroup = repoVariables[1],
            imageName = repoVariables[2],
            imageTag = repoVariables.getOrNull(3)
        )
    }
}
