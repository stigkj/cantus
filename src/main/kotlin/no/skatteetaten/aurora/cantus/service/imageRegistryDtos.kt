package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode

data class ImageTagsResponseDto(val tags: List<String>)

data class ImageManifestResponseDto(
    val contentType: String,
    val dockerContentDigest: String,
    val manifestBody: JsonNode
)

data class ImageManifestDto(
    val auroraVersion: String? = null,
    val appVersion: String? = null,
    val buildStarted: String? = null,
    val buildEnded: String? = null,
    val dockerVersion: String,
    val dockerDigest: String,
    val java: JavaImageDto? = null,
    val jolokiaVersion: String? = null,
    val nodeVersion: String? = null
)

data class JavaImageDto(
    val major: String,
    val minor: String,
    val build: String
) {
    companion object {
        fun fromEnvMap(envMap: Map<String, String>): JavaImageDto? {
            return if (
                envMap["JAVA_VERSION_MAJOR"] == null ||
                envMap["JAVA_VERSION_MINOR"] == null ||
                envMap["JAVA_VERSION_BUILD"] == null
            ) {
                null
            } else {
                JavaImageDto(
                    // These cannot be null as there is a null check above
                    major = envMap["JAVA_VERSION_MAJOR"]!!,
                    minor = envMap["JAVA_VERSION_MINOR"]!!,
                    build = envMap["JAVA_VERSION_BUILD"]!!
                )
            }
        }
    }
}

data class ImageTagTypedDto(val name: String, val type: ImageTagType = ImageTagType.typeOf(name))
data class ImageTagsWithTypeDto(val tags: List<ImageTagTypedDto>)

enum class ImageTagType {
    LATEST,
    SNAPSHOT,
    MAJOR,
    MINOR,
    BUGFIX,
    AURORA_VERSION,
    AURORA_SNAPSHOT_VERSION,
    COMMIT_HASH;

    companion object {
        fun typeOf(tag: String): ImageTagType {
            return when {
                tag.toLowerCase() == "latest" -> ImageTagType.LATEST
                tag.toLowerCase().endsWith("-snapshot") -> ImageTagType.SNAPSHOT
                tag.toLowerCase().startsWith("snapshot-") -> ImageTagType.AURORA_SNAPSHOT_VERSION
                // It is important that COMMIT_HASH is processed before MAJOR to avoid a hash like 1984012 to be
                // considered a MAJOR version (although, technically it coulld be major version it is not very likely).
                tag.matches(Regex("^[0-9abcdef]{7}$")) -> ImageTagType.COMMIT_HASH
                tag.matches(Regex("^\\d+$")) -> ImageTagType.MAJOR
                tag.matches(Regex("^\\d+\\.\\d+$")) -> ImageTagType.MINOR
                tag.matches(Regex("^\\d+\\.\\d+\\.\\d+$")) -> ImageTagType.BUGFIX
                else -> ImageTagType.AURORA_VERSION
            }
        }
    }
}
