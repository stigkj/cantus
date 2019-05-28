package no.skatteetaten.aurora.cantus.controller

import io.netty.handler.timeout.ReadTimeoutException
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import reactor.core.publisher.toMono
import java.time.Duration

private const val blockTimeout: Long = 30

fun <T> Mono<T>.blockAndHandleError(
    duration: Duration = Duration.ofSeconds(blockTimeout),
    imageRepoCommand: ImageRepoCommand? = null
) =
    this.handleError(imageRepoCommand).toMono().block(duration)

fun <T> Mono<T>.handleError(imageRepoCommand: ImageRepoCommand?) =
    try {
        this.doOnError {
            when (it) {
                is WebClientResponseException -> throw SourceSystemException(
                    message = "Error in response, status=${it.statusCode} message=${it.statusText}",
                    cause = it,
                    sourceSystem = imageRepoCommand?.registry
                )
                is SourceSystemException -> throw it
                else -> throw CantusException("Error in response or request (${it::class.simpleName})", it)
            }
        }
    } catch (e: ReadTimeoutException) {
        val imageMsg = imageRepoCommand?.let {
            "imageGroup=\"${it.imageGroup}\" imageName=\"${it.imageName}\" imageTag=\"${it.imageTag}\""
        } ?: "no existing ImageRepoCommand"

        throw SourceSystemException(
            message = "Timeout when calling docker registry, $imageMsg",
            cause = e,
            sourceSystem = imageRepoCommand?.registry
        )
    }

fun ClientResponse.handleStatusCodeError(sourceSystem: String?) {

    val statusCode = this.statusCode()

    if (statusCode.is2xxSuccessful) {
        return
    }

    val message = when {
        statusCode.is4xxClientError -> {
            when (statusCode.value()) {
                404 -> "Resource could not be found"
                400 -> "Invalid request"
                403 -> "Forbidden"
                else -> "There has occurred a client error"
            }
        }
        statusCode.is5xxServerError -> {
            when (statusCode.value()) {
                500 -> "An internal server error has occurred in the docker registry"
                else -> "A server error has occurred"
            }
        }

        else ->
            "Unknown error occurred"
    }

    throw SourceSystemException(
        message = "$message status=${statusCode.value()} message=${statusCode.reasonPhrase}",
        sourceSystem = sourceSystem
    )
}
