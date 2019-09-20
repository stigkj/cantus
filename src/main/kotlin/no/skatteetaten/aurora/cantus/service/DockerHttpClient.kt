package no.skatteetaten.aurora.cantus.service

import com.fasterxml.jackson.databind.JsonNode
import mu.KotlinLogging
import no.skatteetaten.aurora.cantus.controller.BadRequestException
import no.skatteetaten.aurora.cantus.controller.ImageRepoCommand
import no.skatteetaten.aurora.cantus.controller.SourceSystemException
import no.skatteetaten.aurora.cantus.controller.blockAndHandleError
import no.skatteetaten.aurora.cantus.controller.blockAndHandleErrorWithRetry
import no.skatteetaten.aurora.cantus.controller.handleError
import no.skatteetaten.aurora.cantus.controller.handleStatusCodeError
import no.skatteetaten.aurora.cantus.controller.retryRepoCommand
import no.skatteetaten.aurora.cantus.createObjectMapper
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpHeaders.AUTHORIZATION
import org.springframework.http.HttpHeaders.EMPTY
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.body
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import reactor.core.publisher.switchIfEmpty
import java.time.Duration

private val logger = KotlinLogging.logger {}

const val manifestV2 = "application/vnd.docker.distribution.manifest.v2+json"

const val uploadUUIDHeader = "Docker-Upload-UUID"
const val dockerContentDigestLabel = "Docker-Content-Digest"

@Service
class DockerHttpClient(
    val webClient: WebClient
) {
    val dockerManfestAccept: List<MediaType> = listOf(
        MediaType.valueOf(manifestV2)
    )

    fun getUploadUUID(
        to: ImageRepoCommand
    ): String {
        val (_, headers) = to.createRequest(
            method = HttpMethod.POST,
            path = "{imageGroup}/{imageName}/blobs/uploads/"
        )
            .headers { headers ->
                headers.contentLength = 0L
            }
            .exchange()
            .performBodyAndHeader(to)

        return headers[uploadUUIDHeader]?.first() ?: throw SourceSystemException(
            message = "Response to generate UUID header did not succeed",
            sourceSystem = to.registry
        )
    }

    fun putManifest(
        to: ImageRepoCommand,
        manifest: ImageManifestResponseDto
    ): Boolean {
        val manifestBody = createObjectMapper().writeValueAsString(manifest.manifestBody)
        return to.createRequest(method = HttpMethod.PUT, path = "{imageGroup}/{imageName}/manifests/{imageTag}")
            .headers { headers ->
                headers.contentType = MediaType.valueOf(manifest.contentType)
            }
            .body(BodyInserters.fromObject(manifestBody))
            .retrieve()
            .bodyToMono<JsonNode>()
            .blockAndHandleErrorWithRetry(
                "operation=PUT_MANIFEST registry=${to.fullRepoCommand} manifest=$manifestBody contentType=${manifest.contentType}",
                to
            ).let { true }
    }

    fun uploadLayer(
        to: ImageRepoCommand,
        uuid: String,
        digest: String,
        data: Mono<ByteArray>
    ): Boolean {
        return to.createRequest(
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
    }

    fun getImageManifest(imageRepoCommand: ImageRepoCommand): ImageManifestResponseDto {

        if (imageRepoCommand.imageTag.isNullOrEmpty()) throw BadRequestException("Invalid url=${imageRepoCommand.fullRepoCommand}")

        val (body, headers) = imageRepoCommand.createRequest("{imageGroup}/{imageName}/manifests/{imageTag}")
            .headers {
                it.accept = dockerManfestAccept
            }.exchange()
            .retryRepoCommand("operation=GET_MANIFEST registry=${imageRepoCommand.fullRepoCommand}")
            .performBodyAndHeader(imageRepoCommand)

        val manifest: JsonNode = body ?: throw SourceSystemException(
            message = "Manifest not found for image ${imageRepoCommand.manifestRepo}",
            sourceSystem = imageRepoCommand.registry
        )

        val contentType = headers["Content-Type"]?.first() ?: ""

        if (contentType != manifestV2) {
            logger.info("Old image manifest detected for image=${imageRepoCommand.artifactRepo}:${imageRepoCommand.imageTag}")
            throw SourceSystemException(
                message = "Only v2 manifest is supported. contentType=$contentType image=${imageRepoCommand.artifactRepo}:${imageRepoCommand.imageTag}",
                sourceSystem = imageRepoCommand.registry
            )
        }

        val contentDigestLabel: String = headers[dockerContentDigestLabel]?.first() ?: throw SourceSystemException(
            message = "Required header=$dockerContentDigestLabel is not present",
            sourceSystem = imageRepoCommand.registry
        )

        return ImageManifestResponseDto(contentType, contentDigestLabel, manifest)
    }

    fun getImageTags(imageRepoCommand: ImageRepoCommand): ImageTagsResponseDto? {

        return imageRepoCommand.createRequest("/{imageGroup}/{imageName}/tags/list")
            .retrieve()
            .bodyToMono<ImageTagsResponseDto>()
            .blockAndHandleError(imageRepoCommand = imageRepoCommand)
    }

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
        imageRepoCommand.createRequest(
            path = "{imageGroup}/{imageName}/blobs/{digest}",
            pathVariables = mapOf("digest" to digest)
        )
            .retrieve()
            .bodyToMono<ByteArray>()

    private fun getBlob(
        imageRepoCommand: ImageRepoCommand,
        digest: String
    ): ByteArray? {
        return imageRepoCommand.createRequest(
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
        val result = imageRepoCommand.createRequest(
            method = HttpMethod.HEAD,
            path = "{imageGroup}/{imageName}/blobs/$digest"
        )
            .retrieve()
            .bodyToMono<ByteArray>()
            .map { true } // We need this to turn it into a boolean
            .switchIfEmpty { Mono.just(true) }
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

    private fun ImageRepoCommand.createRequest(
        path: String,
        method: HttpMethod = HttpMethod.GET,
        pathVariables: Map<String, String> = emptyMap()
    ) = webClient
        .method(method)
        .uri(
            "${this.url}/$path",
            this.mappedTemplateVars + pathVariables
        )
        .headers { headers ->
            this.token?.let {
                headers.set(AUTHORIZATION, "${this.authType} $it")
            }
        }

    private fun Mono<ClientResponse>.performBodyAndHeader(imageRepoCommand: ImageRepoCommand) =
        this.flatMap { resp: ClientResponse ->
            if (!resp.statusCode().is2xxSuccessful) {
                resp.handleStatusCodeError<Pair<JsonNode?, HttpHeaders>>(imageRepoCommand.registry)
            } else {
                resp.bodyToMono<JsonNode>().map { body ->
                    body to resp.headers().asHttpHeaders()
                }.switchIfEmpty(Mono.just(null to resp.headers().asHttpHeaders()))
            }
        }.handleError(imageRepoCommand)
            .block(Duration.ofSeconds(5))
            ?: null to EMPTY
    // The line above must be here to get the types to align but it will never happen. We switch on Empty in the flagMap block
}