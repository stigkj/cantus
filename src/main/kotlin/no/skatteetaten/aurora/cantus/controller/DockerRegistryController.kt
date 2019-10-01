package no.skatteetaten.aurora.cantus.controller

import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.slf4j.MDCContext
import mu.KotlinLogging
import no.skatteetaten.aurora.cantus.AuroraIntegration
import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import no.skatteetaten.aurora.cantus.service.ImageManifestDto
import no.skatteetaten.aurora.cantus.service.ImageTagsWithTypeDto
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.util.StopWatch
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class TagUrlsWrapper(val tagUrls: List<String>)

private val logger = KotlinLogging.logger {}

@RestController
class DockerRegistryController(
    val dockerRegistryService: DockerRegistryService,
    val imageTagResourceAssembler: ImageTagResourceAssembler,
    val imageRepoCommandAssembler: ImageRepoCommandAssembler,
    val threadPoolContext: ExecutorCoroutineDispatcher,
    val aurora: AuroraIntegration
) {

    /*
      TODO: For now the bearer token is only for the push registry, we need to create a composite token in the future if pull demands authroization
     */
    @PostMapping("/tag")
    fun tagDockerImage(
        @RequestBody tagCommand: TagCommand,
        @RequestHeader(required = true, value = HttpHeaders.AUTHORIZATION) bearerToken: String
    ): ResponseEntity<AuroraResponse<TagCommandResource>> {
        val from = imageRepoCommandAssembler.createAndValidateCommand(tagCommand.from)
        val to = imageRepoCommandAssembler.createAndValidateCommand(tagCommand.to, bearerToken)

        return try {
            if (from.imageTag == null) throw BadRequestException("From spec=${tagCommand.from} does not contain a tag")
            if (to.imageTag == null) throw BadRequestException("To spec=${tagCommand.from} does not contain a tag")

            val result = dockerRegistryService.tagImage(from, to)
            ResponseEntity(
                AuroraResponse(
                    success = result,
                    message = "${from.fullRepoCommand} -> ${to.fullRepoCommand}",
                    items = listOf(TagCommandResource(result))
                ),
                HttpStatus.OK
            )
        } catch (e: Exception) {
            logger.debug("Failed tagging exception occured")
            val status = if (e is BadRequestException) {
                HttpStatus.BAD_REQUEST
            } else {
                HttpStatus.INTERNAL_SERVER_ERROR
            }
            // TODO: the http error in here is an authentication error then we should perhaps throw another status?
            ResponseEntity(
                AuroraResponse(success = false, failure = listOf(CantusFailure(to.fullRepoCommand, e))),
                status
            )
        }
    }

    @PostMapping("/manifest")
    fun getManifestInformationList(
        @RequestBody tagUrlsWrapper: TagUrlsWrapper,
        @RequestHeader(required = false, value = HttpHeaders.AUTHORIZATION) bearerToken: String?
    ): AuroraResponse<ImageTagResource> {

        val watch = StopWatch().apply { this.start() }
        val responses =
            runBlocking(MDCContext() + threadPoolContext) {
                val deferred =
                    tagUrlsWrapper.tagUrls.map {
                        async { getImageTagResource(bearerToken, it) }
                    }
                deferred.map { it.await() }
            }

        return imageTagResourceAssembler.imageTagResourceToAuroraResponse(responses).also {
            watch.stop()
            logger.debug {
                "Get imageManifest tookMs=${watch.totalTimeMillis} urls=${tagUrlsWrapper.tagUrls.joinToString(
                    ","
                )}"
            }
        }
    }

    private fun getImageTagResource(
        bearerToken: String?,
        tagUrl: String
    ): Try<ImageTagResource, CantusFailure> {
        return getResponse(bearerToken, tagUrl) { dockerService, imageRepoCommand ->
            dockerService.getImageManifestInformation(imageRepoCommand)
                .let { imageManifestDto ->
                    imageTagResourceAssembler.toImageTagResource(
                        manifestDto = imageManifestDto,
                        requestUrl = tagUrl
                    )
                }
        }
    }

    @GetMapping("/tags")
    fun getImageTags(
        @RequestParam repoUrl: String,
        @RequestParam filter: String?,
        @RequestHeader(required = false, value = HttpHeaders.AUTHORIZATION) bearerToken: String?
    ): AuroraResponse<TagResource> {
        val watch = StopWatch().apply { this.start() }
        val response =
            getResponse(bearerToken, repoUrl) { dockerService, imageRepoCommand ->
                dockerService.getImageTags(imageRepoCommand, filter).let { tags ->
                    val tagResponse = imageTagResourceAssembler.toTagResource(tags)
                    tagResponse
                }
            }

        return imageTagResourceAssembler.tagResourceToAuroraResponse(response).also {
            watch.stop()
            logger.debug { "Get imageTags tookMs=${watch.totalTimeMillis} url=$repoUrl" }
        }
    }

    private final inline fun <reified T : Any> getResponse(
        bearerToken: String?,
        repoUrl: String,
        fn: (DockerRegistryService, ImageRepoCommand) -> T
    ): Try<T, CantusFailure> {
        return try {
            val imageRepoCommand = imageRepoCommandAssembler.createAndValidateCommand(repoUrl, bearerToken)
            Try.Success(fn(dockerRegistryService, imageRepoCommand))
        } catch (e: Throwable) {
            Try.Failure(CantusFailure(repoUrl, e))
        }
    }
}

@Component
class ImageTagResourceAssembler(val auroraResponseAssembler: AuroraResponseAssembler) {
    fun imageTagResourceToAuroraResponse(resources: List<Try<ImageTagResource, CantusFailure>>) =
        auroraResponseAssembler.toAuroraResponse(resources)

    fun tagResourceToAuroraResponse(resources: Try<List<TagResource>, CantusFailure>) =
        auroraResponseAssembler.toAuroraResponse(resources)

    fun toTagResource(imageTagsWithTypeDto: ImageTagsWithTypeDto) =
        imageTagsWithTypeDto.tags.map { TagResource(it.name) }

    fun toImageTagResource(manifestDto: ImageManifestDto, requestUrl: String) =
        ImageTagResource(
            java = JavaImage.fromDto(manifestDto),
            dockerDigest = manifestDto.dockerDigest,
            dockerVersion = manifestDto.dockerVersion,
            appVersion = manifestDto.appVersion,
            auroraVersion = manifestDto.auroraVersion,
            timeline = ImageBuildTimeline.fromDto(manifestDto),
            node = NodeJsImage.fromDto(manifestDto),
            requestUrl = requestUrl
        )
}
