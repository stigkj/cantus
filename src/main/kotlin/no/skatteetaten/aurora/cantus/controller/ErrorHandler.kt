package no.skatteetaten.aurora.cantus.controller

import io.netty.handler.timeout.ReadTimeoutException
import mu.KotlinLogging
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import reactor.core.publisher.toMono
import reactor.retry.RetryExhaustedException
import reactor.retry.retryExponentialBackoff
import java.time.Duration

private const val blockTimeout: Long = 300
private val logger = KotlinLogging.logger {}

fun <T : Any?> Mono<T>.blockAndHandleErrorWithRetry(
    message: String,
    imageRepoCommand: ImageRepoCommand? = null,
    duration: Duration = Duration.ofSeconds(blockTimeout)
) =
    this.retryRepoCommand(message).blockAndHandleError(duration, imageRepoCommand = imageRepoCommand, message = message)

fun <T : Any?> Mono<T>.retryRepoCommand(message: String) = this.retryExponentialBackoff(
    times = 3,
    first = Duration.ofMillis(100),
    max = Duration.ofSeconds(1)
) {
    val e = it.exception()
    val exceptionClass = e::class.simpleName
    if (it.iteration() == 3L) {
        logger.warn {
            "Retry=last $message exception=$exceptionClass message=${e.localizedMessage}"
        }
    } else {
        logger.info {
            "Retry=${it.iteration()} $message exception=$exceptionClass message=\"${e.message}\""
        }
    }
}

fun <T> Mono<T>.blockAndHandleError(
    duration: Duration = Duration.ofSeconds(blockTimeout),
    imageRepoCommand: ImageRepoCommand? = null,
    message: String? = null
) =
    this.handleError(imageRepoCommand, message).toMono().block(duration)

// TODO: Se på error handling i hele denne filen
fun <T> Mono<T>.handleError(imageRepoCommand: ImageRepoCommand?, message: String? = null) =
    this.doOnError {
        when (it) {
            is WebClientResponseException -> it.handleException(imageRepoCommand, message)
            is ReadTimeoutException -> it.handleException(imageRepoCommand, message)
            is RetryExhaustedException -> it.handleException(imageRepoCommand, message)
            else -> it.handleException(message)
        }
    }

private fun RetryExhaustedException.handleException(imageRepoCommand: ImageRepoCommand?, message: String?) {
    val cause = this.cause!!
    val msg =
        "Retry failed after 4 attempts cause=${cause::class.simpleName} lastError=${cause.localizedMessage} $message"
    logger.warn { msg }
    throw SourceSystemException(
        message = msg,
        cause = this,
        sourceSystem = imageRepoCommand?.registry
    )
}

private fun WebClientResponseException.handleException(imageRepoCommand: ImageRepoCommand?, message: String?) {
    val msg =
        "Error in response, status=$statusCode message=$statusText body=\"${this.responseBodyAsString}\" request_url=\"${this.request?.uri}\" request_method=\"${this.request?.method?.toString()}\" $message"
    logger.warn { msg }
    throw SourceSystemException(
        message = msg,
        cause = this,
        sourceSystem = imageRepoCommand?.registry
    )
}

private fun ReadTimeoutException.handleException(imageRepoCommand: ImageRepoCommand?, message: String?) {
    val imageMsg = imageRepoCommand?.let { cmd ->
        "registry=\"${cmd.registry}\" imageGroup=\"${cmd.imageGroup}\" imageName=\"${cmd.imageName}\" imageTag=\"${cmd.imageTag}\""
    } ?: "no existing ImageRepoCommand"
    val msg = "Timeout when calling docker registry, $imageMsg $message"
    logger.warn { msg }
    throw SourceSystemException(
        message = msg,
        cause = this,
        sourceSystem = imageRepoCommand?.registry
    )
}

private fun Throwable.handleException(message: String?) {
    val msg = "Error in response or request name=${this::class.simpleName} errorMessage=${this.message} $message"
    if (this is SourceSystemException) {
        if (this.message?.contains("MANIFEST_UNKNOWN") == true) logger.info(
            "The image or image metadata is not present in Docker Registry",
            this
        )
        else logger.error(this) { }
        throw this
    } else {
        logger.error(this) { msg }
        throw CantusException(msg, this)
    }
}

fun <T> ClientResponse.handleStatusCodeError(sourceSystem: String?): Mono<T> {
    val statusCode = this.statusCode()
    return this.bodyToMono<String>().switchIfEmpty(Mono.just("")).flatMap { body ->
        Mono.error<T>(
            SourceSystemException(
                message = "body=$body status=${statusCode.value()} message=${statusCode.reasonPhrase}",
                sourceSystem = sourceSystem
            )
        )
    }
}
