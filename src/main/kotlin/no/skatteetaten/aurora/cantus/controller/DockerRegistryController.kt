package no.skatteetaten.aurora.cantus.controller

import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class DockerRegistryController(val dockerRegistryService: DockerRegistryService) {

    @GetMapping("/{affiliation}/{name}/{tag}/manifest")
    fun getManifestInformation(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @PathVariable tag: String,
        @RequestParam(required = false) dockerRegistryUrl: String?
    ): Map<String, String> {
        return dockerRegistryService
            .getImageManifestInformation("$affiliation/$name", tag, dockerRegistryUrl)
            .ifEmpty { throw NoSuchResourceException("Could not find manifest for image $affiliation/$name") }
    }

    @GetMapping("/{affiliation}/{name}/tags")
    fun getImageTags(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @RequestParam(required = false) dockerRegistryUrl: String?
    ) =
        dockerRegistryService.getImageTags("$affiliation/$name", dockerRegistryUrl)
            .ifEmpty { throw NoSuchResourceException("Could not find tags for image $affiliation/$name") }

    @GetMapping("/{affiliation}/{name}/tags/semantic")
    fun getImageTagsSemantic(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @RequestParam(required = false) dockerRegistryUrl: String?
    ) =
        dockerRegistryService.getImageTagsGroupedBySemanticVersion("$affiliation/$name", dockerRegistryUrl)
            .ifEmpty { throw NoSuchResourceException("Not possible to group tags by semantic version. Could not find tags for image $affiliation/$name") }
}