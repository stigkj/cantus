package no.skatteetaten.aurora.cantus

import no.skatteetaten.aurora.cantus.service.ImageManifestDto
import no.skatteetaten.aurora.cantus.service.ImageTagTypedDto
import no.skatteetaten.aurora.cantus.service.ImageTagsWithTypeDto
import no.skatteetaten.aurora.cantus.service.JavaImageDto

data class ImageManifestDtoBuilder(val digest: String = "sha") {
    fun build() = ImageManifestDto(
        auroraVersion = "2",
        dockerVersion = "2",
        dockerDigest = digest,
        appVersion = "2",
        nodeVersion = "2",
        java = JavaImageDto(
            major = "2",
            minor = "0",
            build = "0"
        )
    )
}

data class ImageTagsWithTypeDtoBuilder(
    val namespace: String = "no_skatteetaten_aurora",
    val name: String = "whoami",
    val tags: List<String> = listOf("0", "0.0", "0.0.0")
) {
    fun build() = ImageTagsWithTypeDto(tags.map { ImageTagTypedDto(it) })
}
