package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode
import mu.KotlinLogging
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import no.skatteetaten.aurora.cantus.controller.blockAndHandleError
import no.skatteetaten.aurora.cantus.controller.blockAndHandleErrorWithRetry
import no.skatteetaten.aurora.cantus.createObjectMapper
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.http.HttpHeaders.EMPTY
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.body
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono

private val logger = KotlinLogging.logger {}

const val MANIFEST_V2_MEDIATYPE_VALUE = "application/vnd.docker.distribution.manifest.v2+json"

private const val BLOCK_TIMEOUT_IN_SECONDS = 5L

@Service
class DockerHttpClient(
    val webClient: WebClient
) {

    fun getUploadUUID(
        to: ImageRepoCommand
    ): String {
        val manifestResponse = webClient.request(
            imageRepoCommand = to,
            method = HttpMethod.POST,
            path = "{imageGroup}/{imageName}/blobs/uploads/"
        )
            .body(BodyInserters.empty<Unit>())
            .retrieve()
            .toEntity(JsonNode::class.java)
            .blockAndHandleErrorWithRetry("operation=GET_UPLOAD_UUUID registry=${to.fullRepoCommand}")

        return manifestResponse?.headers?.getFirst("Docker-Upload-UUID")
            ?: throw SourceSystemException(
                message = "Response to generate UUID header did not succeed",
                sourceSystem = to.registry
            )
    }

    fun putManifest(
        to: ImageRepoCommand,
        manifest: ImageManifestResponseDto
    ): Boolean {
        val manifestBody = createObjectMapper().writeValueAsString(manifest.manifestBody)
        return webClient.request(
            imageRepoCommand = to,
            method = HttpMethod.PUT,
            path = "{imageGroup}/{imageName}/manifests/{imageTag}"
        ).headers { headers ->
            headers.contentType = MediaType.valueOf(manifest.contentType)
        }.body(BodyInserters.fromValue(manifestBody))
            .retrieve()
            .bodyToMono<JsonNode>()
            .blockAndHandleErrorWithRetry(
                "operation=PUT_MANIFEST registry=${to.fullRepoCommand}  " +
                    "manifest=$manifestBody contentType=${manifest.contentType}",
                to
            ).let { true }
    }

    fun uploadLayer(
        to: ImageRepoCommand,
        uuid: String,
        digest: String,
        data: Mono<ByteArray>
    ): Boolean = webClient.request(
        imageRepoCommand = to,
        method = HttpMethod.PUT, path = "{imageGroup}/{imageName}/blobs/uploads/{uuid}?digest={digest}",
        pathVariables = mapOf("uuid" to uuid, "digest" to digest)
    )
        .body(data)
        .headers { headers ->
            headers.contentType = MediaType.APPLICATION_OCTET_STREAM
        }
        .retrieve()
        .bodyToMono<JsonNode>()
        .blockAndHandleErrorWithRetry(
            "operation=UPLOAD_LAYER registry=${to.artifactRepo} uuid=$uuid digest=$digest",
            to
        ).let { true }

    fun getImageManifest(imageRepoCommand: ImageRepoCommand): ImageManifestResponseDto {
        val response = webClient
            .request(imageRepoCommand, "{imageGroup}/{imageName}/manifests/{imageTag}")
            .headers {
                it.accept = listOf(MediaType.valueOf(MANIFEST_V2_MEDIATYPE_VALUE))
            }.retrieve()
            .toEntity(JsonNode::class.java)
            .blockAndHandleErrorWithRetry(
                "operation=GET_IMAGE_MANIFEST registry=${imageRepoCommand.fullRepoCommand}",
                imageRepoCommand
            )

        val headers = response?.headers ?: EMPTY

        val manifest = response?.body ?: throw SourceSystemException(
            message = "Manifest not found for image ${imageRepoCommand.manifestRepo}",
            sourceSystem = imageRepoCommand.registry
        )

        val contentType = headers.contentType ?: throw SourceSystemException(
            message = "Required header=Content-Type is not present",
            sourceSystem = imageRepoCommand.registry
        )

        val contentDigestLabel = headers.getFirst("Docker-Content-Digest")
            ?: throw SourceSystemException(
                message = "Required header=Docker-Content-Digest is not present",
                sourceSystem = imageRepoCommand.registry
            )

        return ImageManifestResponseDto(contentType.toString(), contentDigestLabel, manifest)
    }

    fun getImageTags(imageRepoCommand: ImageRepoCommand): ImageTagsResponseDto? = webClient
        .request(imageRepoCommand, "/{imageGroup}/{imageName}/tags/list")
        .retrieve()
        .bodyToMono<ImageTagsResponseDto>()
        .blockAndHandleError(imageRepoCommand = imageRepoCommand)

    fun getConfig(imageRepoCommand: ImageRepoCommand, digest: String) =
        this.getBlob(
            imageRepoCommand, digest
        )?.let {
            createObjectMapper().readTree(it)
        } ?: throw SourceSystemException(
            message = "Unable to retrieve V2 manifest for ${imageRepoCommand.artifactRepo}/$digest",
            sourceSystem = imageRepoCommand.registry
        )

    fun getLayer(imageRepoCommand: ImageRepoCommand, digest: String): Mono<ByteArray> =
        webClient.request(
            imageRepoCommand = imageRepoCommand,
            path = "{imageGroup}/{imageName}/blobs/{digest}",
            pathVariables = mapOf("digest" to digest)
        )
            .retrieve()
            .bodyToMono()

    private fun getBlob(
        imageRepoCommand: ImageRepoCommand,
        digest: String
    ): ByteArray? {
        return webClient.request(
            imageRepoCommand = imageRepoCommand,
            path = "{imageGroup}/{imageName}/blobs/{digest}",
            pathVariables = mapOf("digest" to digest)
        )
            .retrieve()
            .bodyToMono<ByteArray>()
            .blockAndHandleErrorWithRetry(
                "operation=GET_BLOB registry=${imageRepoCommand.artifactRepo}",
                imageRepoCommand
            )
    }

    fun digestExistInRepo(
        imageRepoCommand: ImageRepoCommand,
        digest: String
    ): Boolean {
        val result = webClient.request(
            imageRepoCommand = imageRepoCommand,
            method = HttpMethod.HEAD,
            path = "{imageGroup}/{imageName}/blobs/$digest"
        ).retrieve()
            .bodyToMono<ByteArray>()
            .map { true } // We need this to turn it into a boolean
            .switchIfEmpty(Mono.just(true))
            .onErrorResume { e ->
                if (e is WebClientResponseException && e.statusCode == HttpStatus.NOT_FOUND) {
                    Mono.just(false)
                } else {
                    Mono.error(e)
                }
            }
            .blockAndHandleErrorWithRetry(
                "operation=BLOB_EXIST registry=${imageRepoCommand.artifactRepo} digest=$digest",
                imageRepoCommand
            )
        return result ?: false
    }

    private fun WebClient.request(
        imageRepoCommand: ImageRepoCommand,
        path: String,
        method: HttpMethod = HttpMethod.GET,
        pathVariables: Map<String, String> = emptyMap()
    ) = this
        .method(method)
        .uri(
            "${imageRepoCommand.url}/$path",
            imageRepoCommand.mappedTemplateVars + pathVariables
        )
        .headers { headers ->
            imageRepoCommand.token?.let {
                headers.set(AUTHORIZATION, "${imageRepoCommand.authType} $it")
            }
        }
}
