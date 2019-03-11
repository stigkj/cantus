package no.skatteetaten.aurora.cantus.controller

import com.fasterxml.jackson.annotation.JsonIgnore
import mu.KotlinLogging
import no.skatteetaten.aurora.cantus.service.ImageManifestDto
import no.skatteetaten.aurora.cantus.service.ImageTagType
import org.springframework.stereotype.Component
import uk.q3c.rest.hal.HalResource
import java.time.Instant

private val logger = KotlinLogging.logger {}

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

data class NodeJsImage(
    val nodeJsVersion: String
) {
    companion object {
        fun fromDto(dto: ImageManifestDto): NodeJsImage? {
            if (dto.nodeVersion == null) {
                return null
            }

            return NodeJsImage(dto.nodeVersion)
        }
    }
}

sealed class Try<out A, out B> {
    class Success<A>(val value: A) : Try<A, Nothing>()
    class Failure<B>(val value: B) : Try<Nothing, B>()
}

fun <S : Any, T : Any> List<Try<S, T>>.getSuccessAndFailures(): Pair<List<S>, List<T>> {
    val items = this.mapNotNull {
        if (it is Try.Success) {
            it.value
        } else null
    }

    val failure = this.mapNotNull {
        if (it is Try.Failure) {
            if (it.value is CantusFailure) logger.debug(it.value.error) { "An error has occurred" }
            it.value
        } else null
    }

    return Pair(items, failure)
}

fun <S, T> Try<S, T>.getSuccessAndFailures(): Pair<List<S>, List<T>> {
    val item = if (this is Try.Success) {
        listOf(this.value)
    } else emptyList()
    val failure = if (this is Try.Failure) {
        listOf(this.value)
    } else emptyList()

    return Pair(item, failure)
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

    fun <T : HalResource> toAuroraResponse(responses: List<Try<T, CantusFailure>>): AuroraResponse<T> {
        val (items, failures) = responses.getSuccessAndFailures()

        return AuroraResponse(
            success = failures.isEmpty(),
            message = if (failures.isNotEmpty()) failures.first().errorMessage else "Success",
            items = items,
            failure = failures
        )
    }

    fun <T : HalResource> toAuroraResponse(responses: Try<List<T>, CantusFailure>): AuroraResponse<T> {
        val itemsAndFailure = responses.getSuccessAndFailures()
        val items = itemsAndFailure.first.firstOrNull() ?: emptyList()
        val failures = itemsAndFailure.second

        return AuroraResponse(
            success = failures.isEmpty(),
            message = if (failures.isNotEmpty()) failures.first().errorMessage else "Success",
            items = items,
            failure = failures
        )
    }
}
