package no.skatteetaten.aurora.cantus.controller

import no.skatteetaten.aurora.cantus.service.ImageManifestDto
import no.skatteetaten.aurora.cantus.service.ImageTagType
import uk.q3c.rest.hal.HalResource
import java.time.Instant

data class TagResource(val name: String, val type: ImageTagType = ImageTagType.typeOf(name)) : HalResource()

data class GroupedTagResource(
    val group: String,
    val tagResource: List<TagResource>,
    val itemsInGroup: Int = tagResource.size
) : HalResource()

data class ImageTagResource(
    val auroraVersion: String? = null,
    val appVersion: String? = null,
    val timeline: ImageBuildTimeline,
    val dockerVersion: String,
    val dockerDigest: String,
    val java: JavaImage? = null,
    val node: NodeImage? = null
) : HalResource()

data class JavaImage(
    val major: String,
    val minor: String,
    val build: String,
    val jolokia: String?
) {
    companion object {
        fun fromDto(dto: ImageManifestDto): JavaImage? {
            if (dto.java == null) {
                return null
            }

            return JavaImage(
                major = dto.java.major,
                minor = dto.java.minor,
                build = dto.java.build,
                jolokia = dto.jolokiaVersion
            )
        }
    }
}

data class ImageBuildTimeline(
    val buildStarted: Instant?,
    val buildEnded: Instant?
) {
    companion object {
        fun fromDto(dto: ImageManifestDto): ImageBuildTimeline {
            return ImageBuildTimeline(
                try {
                    Instant.parse(dto.buildStarted)
                } catch (e: Exception) {
                    null
                },
                try {
                    Instant.parse(dto.buildEnded)
                } catch (e: Exception) {
                    null
                }
            )
        }
    }
}

data class NodeImage(
    val nodeVersion: String
) {
    companion object {
        fun fromDto(dto: ImageManifestDto): NodeImage? {
            if (dto.nodeVersion == null) {
                return null
            }

            return NodeImage(dto.nodeVersion)
        }
    }
}

data class AuroraResponse<T : HalResource>(
    val items: List<T> = emptyList(),
    val success: Boolean = true,
    val message: String = "OK",
    val exception: Throwable? = null,
    val count: Int = items.size
) : HalResource() {

    // TODO: trenger vi denne?
    val item: T?
        get() {
            if (count == 1) {
                return items.first()
            }
            return null
        }
}
