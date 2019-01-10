package no.skatteetaten.aurora.cantus.controller

class NoSuchResourceException(message: String) : RuntimeException(message)
class DockerRegistryException(message: String) : RuntimeException(message)
class BadRequestException(message: String) : RuntimeException(message)