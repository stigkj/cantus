package no.skatteetaten.aurora.cantus.controller

import com.fasterxml.jackson.annotation.JsonIgnore
import mu.KotlinLogging
import no.skatteetaten.aurora.cantus.service.ImageManifestDto
import no.skatteetaten.aurora.cantus.service.ImageTagType
import org.springframework.stereotype.Component
import uk.q3c.rest.hal.HalResource
import java.time.Instant

private val logger = KotlinLogging.logger {}

data class TagCommandResource(val result: Boolean) : HalResource()

data class TagResource(val name: String, val type: ImageTagType = ImageTagType.typeOf(name)) : HalResource()

data class ImageTagResource(
    val auroraVersion: String? = null,
    val appVersion: String? = null,
    val timeline: ImageBuildTimeline,
    val dockerVersion: String,
    val dockerDigest: String,
    val java: JavaImage? = null,
    val node: NodeJsImage? = null,
    val requestUrl: String
) : HalResource()

data class JavaImage(
    val major: String,
    val minor: String,
    val build: String,
    val jolokia: String?
) {
    companion object {
        fun fromDto(dto: ImageManifestDto): JavaImage? =
            when (dto.java) {
                null -> null
                else -> JavaImage(
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
        fun fromDto(dto: ImageManifestDto): ImageBuildTimeline =
            ImageBuildTimeline(
                buildStarted = runCatching { Instant.parse(dto.buildStarted) }.getOrNull(),
                buildEnded = runCatching { Instant.parse(dto.buildEnded) }.getOrNull()
            )
    }
}

data class NodeJsImage(
    val nodeJsVersion: String
) {
    companion object {
        fun fromDto(dto: ImageManifestDto): NodeJsImage? =
            dto.nodeVersion?.let { NodeJsImage(it) }
    }
}

data class CantusFailure(
    val url: String,
    @JsonIgnore val error: Throwable? = null
) {
    val errorMessage: String = error?.let { it.message ?: "Unknown error (${it::class.simpleName})" } ?: ""
}

data class AuroraResponse<T : HalResource?>(
    val items: List<T> = emptyList(),
    val failure: List<CantusFailure> = emptyList(),
    val success: Boolean = true,
    val message: String = "OK",
    val failureCount: Int = failure.size,
    val successCount: Int = items.size,
    val count: Int = failureCount + successCount
) : HalResource()

@Component
class AuroraResponseAssembler {
    fun <T : HalResource> toAuroraResponse(responses: List<Result<T>>): AuroraResponse<T> {
        val items = responses.mapNotNull { it.getOrNull() }
        val failures = responses
            .mapNotNull { it.exceptionOrNull() }
            .map {
                when (it) {
                    is RequestResultException -> CantusFailure(it.repoUrl, it.cause)
                    else -> CantusFailure("Unrecognized error happened", it.cause)
                }
            }
        return AuroraResponse(
            success = failures.isEmpty(),
            message = if (failures.isNotEmpty()) failures.first().errorMessage else "Success",
            items = items,
            failure = failures
        )
    }

    fun <T : HalResource> toAuroraResponse(responses: Result<List<T>>): AuroraResponse<T> =
        toAuroraResponse(
            responses.getOrNull()?.map {
                Result.success(it)
            } ?: listOf(
                Result.failure(
                    responses.exceptionOrNull() ?: CantusException("Result did not contain any data")
                )
            )
        )
}
