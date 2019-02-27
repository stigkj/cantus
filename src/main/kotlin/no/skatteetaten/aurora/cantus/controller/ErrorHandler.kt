package no.skatteetaten.aurora.cantus.controller

import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import reactor.core.publisher.toMono
import java.time.Duration

private val blockTimeout: Long = 30

fun <T> Mono<T>.blockAndHandleError(
    duration: Duration = Duration.ofSeconds(blockTimeout),
    sourceSystem: String? = null
) =
    this.handleError(sourceSystem).toMono().block(duration)

fun <T> Mono<T>.handleError(sourceSystem: String?) =
    this.doOnError {
        when (it) {
            is WebClientResponseException -> throw SourceSystemException(
                message = "Error in response, status=${it.statusCode} message=${it.statusText}",
                cause = it,
                sourceSystem = sourceSystem
            )
            is SourceSystemException -> throw it
            else -> throw CantusException("Unknown error in response or request", it)
        }
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
