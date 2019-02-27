package no.skatteetaten.aurora.cantus.controller

import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class DockerRegistryControllerOld(
    val dockerRegistryService: DockerRegistryService,
    val imageTagResourceAssembler: ImageTagResourceAssemblerOld,
    val imageRepoCommandAssembler: ImageRepoDtoAssembler
) {

    @GetMapping("/{affiliation}/{name}/{tag}/manifest")
    fun getManifestInformation(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @PathVariable tag: String,
        @RequestParam(required = false) dockerRegistryUrl: String?,
        @RequestHeader(required = false, value = "Authorization") bearerToken: String?
    ): AuroraResponseOld<ImageTagResource> {
        val imageRepoCommand = imageRepoCommandAssembler.createAndValidateCommand(
            overrideRegistryUrl = dockerRegistryUrl,
            name = name,
            namespace = affiliation,
            tag = tag,
            bearerToken = bearerToken
        )
        return dockerRegistryService
            .getImageManifestInformation(imageRepoCommand).let { manifestDto ->
                imageTagResourceAssembler.toResource(
                    manifestDto,
                    "Successfully retrieved manifest information for image ${imageRepoCommand.manifestRepo}"
                )
            }
    }

    @GetMapping("/{affiliation}/{name}/tags")
    fun getImageTags(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @RequestParam(required = false) dockerRegistryUrl: String?,
        @RequestHeader(required = false, value = "Authorization") bearerToken: String?
    ): AuroraResponseOld<TagResource> {
        val imageRepoCommand = imageRepoCommandAssembler.createAndValidateCommand(
            overrideRegistryUrl = dockerRegistryUrl,
            name = name,
            namespace = affiliation,
            bearerToken = bearerToken
        )
        return dockerRegistryService.getImageTags(imageRepoCommand).let { imageTagsWithTypeDto ->
            imageTagResourceAssembler.toResource(
                imageTagsWithTypeDto,
                "Successfully retrieved tags for image ${imageRepoCommand.defaultRepo}"
            )
        }
    }

    @GetMapping("/{affiliation}/{name}/tags/semantic")
    fun getImageTagsSemantic(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @RequestParam(required = false) dockerRegistryUrl: String?,
        @RequestHeader(required = false, value = "Authorization") bearerToken: String?
    ): AuroraResponseOld<GroupedTagResource> {
        val imageRepoCommand = imageRepoCommandAssembler.createAndValidateCommand(
            overrideRegistryUrl = dockerRegistryUrl,
            name = name,
            namespace = affiliation,
            bearerToken = bearerToken
        )

        return dockerRegistryService.getImageTags(imageRepoCommand).let { imageTagsWithTypeDto ->
            imageTagResourceAssembler.toGroupedResource(
                imageTagsWithTypeDto,
                "Successfully retrieved tags grouped by semantic version for image ${imageRepoCommand.defaultRepo}"
            )
        }
    }
}
