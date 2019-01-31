package no.skatteetaten.aurora.cantus.controller

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.request.WebRequest
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import reactor.core.publisher.Mono
import reactor.core.publisher.toMono
import uk.q3c.rest.hal.HalResource
import java.time.Duration

val blockTimeout: Long = 30

@ControllerAdvice
class ErrorHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(RuntimeException::class)
    fun handleGenericError(e: RuntimeException, request: WebRequest): ResponseEntity<Any>? {
        return handleException(e, request, HttpStatus.INTERNAL_SERVER_ERROR)
    }

    @ExceptionHandler(BadRequestException::class)
    fun handleBadRequest(e: BadRequestException, request: WebRequest): ResponseEntity<Any>? {
        return handleException(e, request, HttpStatus.BAD_REQUEST)
    }

    private fun handleException(e: Exception, request: WebRequest, httpStatus: HttpStatus): ResponseEntity<Any>? {
        val auroraResponse = AuroraResponse<HalResource>(
            success = false,
            message = e.message ?: "Cantus error",
            exception = e

        )
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        logger.debug("Handle exception", e)
        return handleExceptionInternal(e, auroraResponse, headers, httpStatus, request)
    }
}

fun <T> Mono<T>.blockNonNullAndHandleError(
    duration: Duration = Duration.ofSeconds(blockTimeout),
    sourceSystem: String? = null
) =
    this.switchIfEmpty(SourceSystemException("Empty response", sourceSystem = sourceSystem).toMono())
        .blockAndHandleError(duration, sourceSystem)!!

fun <T> Mono<T>.blockAndHandleError(
    duration: Duration = Duration.ofSeconds(blockTimeout),
    sourceSystem: String? = null
) =
    this.handleError(sourceSystem).toMono().block(duration)

private fun <T> Mono<T>.handleError(sourceSystem: String?) =
    this.doOnError {
        when (it) {
            is WebClientResponseException -> throw SourceSystemException(
                message = "Error in response, status:${it.statusCode} message:${it.statusText}",
                cause = it,
                sourceSystem = sourceSystem,
                code = it.statusCode.name
            )
            is SourceSystemException -> throw it
            else -> throw CantusException("Unknown error in response or request", it)
        }
    }
