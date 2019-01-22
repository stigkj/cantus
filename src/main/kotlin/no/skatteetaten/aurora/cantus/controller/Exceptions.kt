package no.skatteetaten.aurora.cantus.controller

class BadRequestException(
    message: String,
    cause: Throwable? = null,
    code: String = "400"
) : CantusException(message, cause, code)

open class CantusException(
    message: String,
    cause: Throwable? = null,
    val code: String = ""
) : java.lang.RuntimeException(message, cause)

class SourceSystemException(
    message: String,
    cause: Throwable? = null,
    code: String = "404",
    val sourceSystem: String? = null
) : CantusException(message, cause, code)