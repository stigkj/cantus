package no.skatteetaten.aurora.cantus.controller

class BadRequestException(message: String) : RuntimeException(message)

open class CantusException(
    message: String,
    cause: Throwable? = null,
    val code: String = ""
) : java.lang.RuntimeException(message, cause)

class SourceSystemException(
    message: String,
    cause: Throwable? = null,
    code: String = "",
    val sourceSystem: String? = null
) : CantusException(message, cause, code)