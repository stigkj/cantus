package no.skatteetaten.aurora.cantus.controller

import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import no.skatteetaten.aurora.cantus.service.ImageManifestDto
import no.skatteetaten.aurora.cantus.service.ImageTagsWithTypeDto
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class DockerRegistryController(
    val dockerRegistryService: DockerRegistryService,
    val imageTagResourceAssembler: ImageTagResourceAssembler
) {

    @GetMapping("/{affiliation}/{name}/{tag}/manifest")
    fun getManifestInformation(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @PathVariable tag: String,
        @RequestParam(required = false) dockerRegistryUrl: String?
    ): AuroraResponse<ImageTagResource> {
        return dockerRegistryService
            .getImageManifestInformation(affiliation, name, tag, dockerRegistryUrl).let { manifestDto ->
                imageTagResourceAssembler.toResource(
                    manifestDto,
                    "Successfully retrieved manifest information for image $affiliation/$name:$tag"
                )
            }
    }

    @GetMapping("/{affiliation}/{name}/tags")
    fun getImageTags(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @RequestParam(required = false) dockerRegistryUrl: String?
    ) = dockerRegistryService.getImageTags(affiliation, name, dockerRegistryUrl).let { imageTagsWithTypeDto ->
        imageTagResourceAssembler.toResource(
            imageTagsWithTypeDto,
            "Successfully retrieved tags for image $affiliation/$name"
        )
    }

    @GetMapping("/{affiliation}/{name}/tags/semantic")
    fun getImageTagsSemantic(
        @PathVariable affiliation: String,
        @PathVariable name: String,
        @RequestParam(required = false) dockerRegistryUrl: String?
    ) =
        dockerRegistryService.getImageTags(affiliation, name, dockerRegistryUrl).let { imageTagsWithTypeDto ->
            imageTagResourceAssembler.toGroupedResource(
                imageTagsWithTypeDto,
                "Successfully retrieved tags grouped by semantic version for image $affiliation/$name"
            )
        }
}

@Component
class ImageTagResourceAssembler {
    fun toResource(manifestDto: ImageManifestDto, message: String): AuroraResponse<ImageTagResource> =
        AuroraResponse(
            success = true,
            message = message,
            items = listOf(
                ImageTagResource(
                    java = JavaImage.fromDto(manifestDto),
                    dockerDigest = manifestDto.dockerDigest,
                    dockerVersion = manifestDto.dockerVersion,
                    appVersion = manifestDto.appVersion,
                    auroraVersion = manifestDto.auroraVersion,
                    timeline = ImageBuildTimeline.fromDto(manifestDto),
                    node = NodeImage.fromDto(manifestDto)
                )
            )
        )

    fun toResource(tags: ImageTagsWithTypeDto, message: String): AuroraResponse<TagResource> =
        AuroraResponse(
            success = true,
            message = message,
            items = tags.tags.map {
                TagResource(
                    name = it.name
                )
            }
        )

    fun toGroupedResource(tags: ImageTagsWithTypeDto, message: String): AuroraResponse<GroupedTagResource> =
        AuroraResponse(
            success = true,
            message = message,
            items = tags.tags.groupBy {
                it.type
            }.map { groupedTag ->
                GroupedTagResource(
                    group = groupedTag.key.toString(),
                    tagResource = groupedTag.value.map {
                        TagResource(
                            name = it.name,
                            type = it.type
                        )
                    }
                )
            }
        )
}