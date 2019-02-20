package no.skatteetaten.aurora.cantus.controller

import mu.KotlinLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.request.WebRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import reactor.core.publisher.Mono
import reactor.core.publisher.toMono
import uk.q3c.rest.hal.HalResource
import java.time.Duration

private val blockTimeout: Long = 30
private val errorLogger = KotlinLogging.logger {}

@ControllerAdvice
class ErrorHandler : ResponseEntityExceptionHandler() {

    @ExceptionHandler(RuntimeException::class)
    fun handleGenericError(e: RuntimeException, request: WebRequest) =
        handleException(e, request, HttpStatus.INTERNAL_SERVER_ERROR)

    @ExceptionHandler(ForbiddenException::class)
    fun handleForbiddenRequest(e: ForbiddenException, request: WebRequest) =
        handleException(e, request, HttpStatus.FORBIDDEN)

    @ExceptionHandler(BadRequestException::class)
    fun handleBadRequest(e: BadRequestException, request: WebRequest) =
        handleException(e, request, HttpStatus.BAD_REQUEST)

    @ExceptionHandler(SourceSystemException::class)
    fun handleSourceSystem(e: SourceSystemException, request: WebRequest) =
        handleException(e, request, HttpStatus.OK)

    private fun handleException(e: Exception, request: WebRequest, httpStatus: HttpStatus): ResponseEntity<Any>? {
        val auroraResponse = AuroraResponse<HalResource>(
            success = false,
            message = e.message ?: "",
            exception = e
        )
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }

        errorLogger.debug(e) { "An exception has occurred with status=${httpStatus.value()} message=${e.message}" }
        return handleExceptionInternal(e, auroraResponse, headers, httpStatus, request)
    }
}

fun <T> Mono<T>.blockAndHandleError(
    duration: Duration = Duration.ofSeconds(blockTimeout),
    sourceSystem: String? = null
) =
    this.handleError(sourceSystem).toMono().block(duration)

fun <T> Mono<T>.handleError(sourceSystem: String?) =
    this.doOnError {
        when (it) {
            is WebClientResponseException -> throw SourceSystemException(
                message = "Error in response, status:${it.statusCode} message:${it.statusText}",
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
