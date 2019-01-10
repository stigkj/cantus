package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.skatteetaten.aurora.cantus.controller.BadRequestException
import no.skatteetaten.aurora.cantus.controller.DockerRegistryException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.RequestEntity
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.RestTemplate
import java.net.URI
import java.util.HashSet

data class DockerRegistryTagResponse(val name: String, val tags: List<String>)

val logger = LoggerFactory.getLogger(DockerRegistryService::class.java)

@Service
class DockerRegistryService(
    val restTemplate: RestTemplate,
    @Value("\${cantus.docker-registry-url}") val dockerRegistryUrl: String,
    @Value("\${cantus.docker-registry-url-allowed}") val dockerRegistryUrlsAllowed: List<String>
) {

    val DOCKER_MANIFEST_V2: String = "application/vnd.docker.distribution.manifest.v2+json"

    val manifestEnvLabels: HashSet<String> = hashSetOf(
        "AURORA_VERSION",
        "IMAGE_BUILD_TIME",
        "APP_VERSION",
        "JOLOKIA_VERSION",
        "JAVA_VERSION_MAJOR",
        "JAVA_VERSION_MINOR",
        "JAVA_VERSION_BUILD",
        "NODE_VERSION"
    )

    val dockerVersionLabel = "docker_version"
    val dockerContentDigestLabel = "Docker-Content-Digest"
    val createdLabel = "created"

    fun getImageManifestInformation(
        imageName: String,
        imageTag: String,
        registryUrl: String? = null
    ): Map<String, String> {
        val url = registryUrl ?: dockerRegistryUrl

        validateDockerRegistryUrl(url, dockerRegistryUrlsAllowed)

        val bodyRequest = createManifestRequest(url, imageName, imageTag)
        val headerRequest = createManifestRequest(url, imageName, imageTag, DOCKER_MANIFEST_V2)

        logger.debug("Retrieving manifest-BODY from $url")
        val responseBodyRequest: ResponseEntity<JsonNode> = restTemplate.exchangeAndLogError(bodyRequest)

        logger.debug("Retrieving manifest-HEAD from $url")
        val responseHeaderRequest: ResponseEntity<JsonNode> = restTemplate.exchangeAndLogError(headerRequest)

        return extractManifestInformation(responseBodyRequest, responseHeaderRequest)
    }

    fun getImageTags(imageName: String, registryUrl: String? = null): List<String> {
        val url = registryUrl ?: dockerRegistryUrl

        validateDockerRegistryUrl(url, dockerRegistryUrlsAllowed)

        val manifestUri = URI("$url/v2/$imageName/tags/list")
        val header = HttpHeaders()

        logger.debug("Retrieving tags from {}", manifestUri)
        val tagsRequest = RequestEntity<JsonNode>(header, HttpMethod.GET, manifestUri)
        val response: ResponseEntity<DockerRegistryTagResponse> = restTemplate.exchangeAndLogError(tagsRequest)
        return response.body?.tags ?: listOf()
    }

    fun getImageTagsGroupedBySemanticVersion(
        imageName: String,
        registryUrl: String? = null
    ): Map<String, List<String>> {
        val tags = getImageTags(imageName, registryUrl)

        logger.debug("Tags are grouped by semantic version")
        return tags.groupBy { ImageTagType.typeOf(it).toString() }
    }

    private fun createManifestRequest(
        registryUrl: String,
        imageName: String,
        imageTag: String,
        headerAccept: String = ""
    ): RequestEntity<JsonNode> {
        val manifestUri = URI("$registryUrl/v2/$imageName/manifests/$imageTag")
        val header = HttpHeaders()
        if (headerAccept != "") header.accept = listOf(MediaType.valueOf(headerAccept))

        return RequestEntity(header, HttpMethod.GET, manifestUri)
    }

    private fun extractManifestInformation(
        responseBodyRequest: ResponseEntity<JsonNode>,
        responseHeaderRequest: ResponseEntity<JsonNode>
    ): Map<String, String> {

        val v1Compatibility = responseBodyRequest.getV1CompatibilityFromManifest()

        val environmentVariables = v1Compatibility.getEnvironmentVariablesFromManifest()

        val imageManifestEnvInformation = environmentVariables
            .filter { manifestEnvLabels.contains(it.key) }
            .mapKeys { it.key.toUpperCase() }

        val dockerVersion = v1Compatibility.getVariableFromManifestBody(dockerVersionLabel)
        val dockerContentDigest = responseHeaderRequest.getVariableFromManifestHeader(dockerContentDigestLabel)
        val created = v1Compatibility.getVariableFromManifestBody(createdLabel)

        val imageManifestConfigInformation = mapOf(
            dockerVersionLabel to dockerVersion,
            dockerContentDigestLabel to dockerContentDigest,
            createdLabel to created
        ).mapKeys { it.key.toUpperCase() }

        return imageManifestEnvInformation + imageManifestConfigInformation
    }

    private fun validateDockerRegistryUrl(urlToValidate: String, alllowedUrls: List<String>) {
        if (!alllowedUrls.any { allowedUrl: String -> urlToValidate == allowedUrl }) throw BadRequestException("Invalid Docker Registry URL")
    }
}

private fun ResponseEntity<JsonNode>.getV1CompatibilityFromManifest() =
    jacksonObjectMapper().readTree(this.body?.get("history")?.get(0)?.get("v1Compatibility")?.asText() ?: "")

private fun JsonNode.getEnvironmentVariablesFromManifest() =
    this.at("/config/Env").associate {
        val (key, value) = it.asText().split("=")
        key to value
    }

private fun JsonNode.getVariableFromManifestBody(label: String) = this.get(label)?.asText() ?: ""
private fun ResponseEntity<JsonNode>.getVariableFromManifestHeader(label: String) = this.headers[label]?.get(0)
    ?: ""

private inline fun <reified T : Any> RestTemplate.exchangeAndLogError(request: RequestEntity<JsonNode>) =
    try {
        this.exchange(request, T::class.java)
    } catch (e: RestClientResponseException) {
        logger.warn("Received error from Docker Registry ${request.url} status: ${e.rawStatusCode} - ${e.statusText}")
        throw DockerRegistryException("Received error from Docker Registry status: ${e.rawStatusCode} - ${e.statusText}")
    }
