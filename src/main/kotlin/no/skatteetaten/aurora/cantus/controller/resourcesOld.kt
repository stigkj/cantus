package no.skatteetaten.aurora.cantus.controller

import no.skatteetaten.aurora.cantus.service.ImageManifestDto
import no.skatteetaten.aurora.cantus.service.ImageTagsWithTypeDto
import org.springframework.stereotype.Component
import uk.q3c.rest.hal.HalResource

data class AuroraResponseOld<T : HalResource>(
    val items: List<T> = emptyList(),
    val success: Boolean = true,
    val message: String = "OK",
    val exception: Throwable? = null,
    val count: Int = items.size
) : HalResource()

@Component
class ImageTagResourceAssemblerOld {
    fun toResource(manifestDto: ImageManifestDto, message: String): AuroraResponseOld<ImageTagResource> =
        AuroraResponseOld(
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
                    node = NodeJsImage.fromDto(manifestDto),
                    requestUrl = ""
                )
            )
        )

    fun toResource(tags: ImageTagsWithTypeDto, message: String): AuroraResponseOld<TagResource> =
        AuroraResponseOld(
            success = true,
            message = message,
            items = tags.tags.map {
                TagResource(
                    name = it.name
                )
            }
        )

    fun toGroupedResource(tags: ImageTagsWithTypeDto, message: String): AuroraResponseOld<GroupedTagResource> =
        AuroraResponseOld(
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