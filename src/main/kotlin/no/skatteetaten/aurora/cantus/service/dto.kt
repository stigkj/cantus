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
            if (envMap["JAVA_VERSION_MAJOR"] == null ||
                envMap["JAVA_VERSION_MINOR"] == null ||
                envMap["JAVA_VERSION_BUILD"] == null
            ) {
                return null
            }

            return JavaImageDto(
                major = envMap["JAVA_VERSION_MAJOR"]!!,
                minor = envMap["JAVA_VERSION_MINOR"]!!,
                build = envMap["JAVA_VERSION_BUILD"]!!
            )
        }
    }
}

data class ImageTagTypedDto(val name: String, val type: ImageTagType = ImageTagType.typeOf(name))
data class ImageTagsWithTypeDto(val tags: List<ImageTagTypedDto>)
