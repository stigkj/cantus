package no.skatteetaten.aurora.cantus.controller

class BadRequestException(
    message: String,
    cause: Throwable? = null
) : CantusException(message, cause)

class ForbiddenException(
    message: String,
    cause: Throwable? = null
) : CantusException(message, cause)

open class CantusException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

class SourceSystemException(
    message: String,
    cause: Throwable? = null,
    val sourceSystem: String? = null
) : CantusException(message, cause)

class RequestResultException(
    val repoUrl: String,
    cause: Throwable? = null
) : RuntimeException("", cause)
