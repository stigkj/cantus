package no.skatteetaten.aurora.cantus.controller

import io.netty.handler.timeout.ReadTimeoutException
import mu.KotlinLogging
import org.springframework.web.reactive.function.UnsupportedMediaTypeException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.Exceptions
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.util.retry.Retry
import java.time.Duration

private const val BLOCK_TIMEOUT: Long = 300
private val logger = KotlinLogging.logger {}

fun <T> Mono<T>.retryWithLog(
    retryConfiguration: RetryConfiguration,
    ignoreAllWebClientResponseException: Boolean = false,
    context: String = ""
): Mono<T> {
    if (retryConfiguration.times == 0L) {
        return this
    }

    return this.retryWhen(
        Retry.backoff(retryConfiguration.times, retryConfiguration.min)
            .maxBackoff(retryConfiguration.max)
            .filter {
                logger.trace(it) {
                    "retryWhen called with exception ${it?.javaClass?.simpleName}, message: ${it?.message}"
                }

                if (ignoreAllWebClientResponseException) {
                    it !is WebClientResponseException
                } else {
                    (it.isServerError() || it !is WebClientResponseException) && it !is UnsupportedMediaTypeException
                }
            }.doBeforeRetry {
                logger.debug {
                    val e = it.failure()
                    val msg = "Retrying failed request times=${it.totalRetries()}, context=$context " +
                        "errorType=${e.javaClass.simpleName} errorMessage=${e.message}"
                    if (e is WebClientResponseException) {
                        "$msg, method=${e.request?.method} uri=${e.request?.uri}"
                    } else {
                        msg
                    }
                }
            }
    )
}

data class RetryConfiguration(
    var times: Long = 3L,
    var min: Duration = Duration.ofMillis(100),
    var max: Duration = Duration.ofSeconds(1)
)

private fun Throwable.isServerError() =
    this is WebClientResponseException && this.statusCode.is5xxServerError

fun <T : Any?> Mono<T>.blockAndHandleErrorWithRetry(
    message: String,
    imageRepoCommand: ImageRepoCommand? = null,
    duration: Duration = Duration.ofSeconds(BLOCK_TIMEOUT)
) =
    this.retryWithLog(RetryConfiguration(), context = message)
        .blockAndHandleError(duration, imageRepoCommand = imageRepoCommand, message = message)

fun <T> Mono<T>.blockAndHandleError(
    duration: Duration = Duration.ofSeconds(BLOCK_TIMEOUT),
    imageRepoCommand: ImageRepoCommand? = null,
    message: String? = null
) =
    this.handleError(imageRepoCommand, message).toMono<T>().block(duration)

// TODO: Se på error handling i hele denne filen
fun <T> Mono<T>.handleError(imageRepoCommand: ImageRepoCommand?, message: String? = null) =
    this.doOnError {
        when {
            Exceptions.isRetryExhausted(it) -> it.handleException(imageRepoCommand, message)
            it is WebClientResponseException -> it.handleException(imageRepoCommand, message)
            it is ReadTimeoutException -> it.handleException(imageRepoCommand, message)
            it is UnsupportedMediaTypeException -> it.handleException(imageRepoCommand, message)
            else -> it.handleException(message)
        }
    }

private fun UnsupportedMediaTypeException.handleException(imageRepoCommand: ImageRepoCommand?, message: String?) {
    logger.info {
        "Old image manfiest detected for image=${imageRepoCommand?.artifactRepo}:${imageRepoCommand?.imageTag}"
    }

    throw SourceSystemException(
        message = "Only v2 manifest is supported. contentType=${this.contentType} " +
            "image=${imageRepoCommand?.artifactRepo}:${imageRepoCommand?.imageTag}",
        sourceSystem = imageRepoCommand?.registry
    )
}

private fun Throwable.handleException(imageRepoCommand: ImageRepoCommand?, message: String?) {
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
        "Error in response, status=$statusCode message=$statusText body=\"${this.responseBodyAsString}\" " +
            "request_url=\"${this.request?.uri}\" request_method=\"${this.request?.method?.toString()}\" $message"
    logger.warn { msg }
    throw SourceSystemException(
        message = msg,
        cause = this,
        sourceSystem = imageRepoCommand?.registry
    )
}

private fun ReadTimeoutException.handleException(imageRepoCommand: ImageRepoCommand?, message: String?) {
    val imageMsg = imageRepoCommand?.let { cmd ->
        "registry=\"${cmd.registry}\" imageGroup=\"${cmd.imageGroup}\" imageName=\"${cmd.imageName}\" " +
            "imageTag=\"${cmd.imageTag}\""
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
