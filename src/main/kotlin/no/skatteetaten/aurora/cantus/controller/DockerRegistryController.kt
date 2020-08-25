package no.skatteetaten.aurora.cantus.controller

import kotlinx.coroutines.Dispatchers
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
      TODO: For now the bearer token is only for the push registry,
       we need to create a composite token in the future if pull demands authroization
     */
    @PostMapping("/tag")
    fun tagDockerImage(
        @RequestBody tagCommand: TagCommand,
        @RequestHeader(required = true, value = HttpHeaders.AUTHORIZATION) bearerToken: String
    ): ResponseEntity<AuroraResponse<TagCommandResource>> {
        val from = imageRepoCommandAssembler.createAndValidateCommand(tagCommand.from)
        val to = imageRepoCommandAssembler.createAndValidateCommand(tagCommand.to, bearerToken)

        return try {
            when {
                from.imageTag == null -> throw BadRequestException("From spec=${tagCommand.from} does not contain a tag")
                to.imageTag == null -> throw BadRequestException("To spec=${tagCommand.to} does not contain a tag")
                else -> {
                    val result = dockerRegistryService.tagImage(from, to)
                    ResponseEntity(
                        AuroraResponse(
                            success = result,
                            message = "${from.fullRepoCommand} -> ${to.fullRepoCommand}",
                            items = listOf(TagCommandResource(result))
                        ),
                        HttpStatus.OK
                    )
                }
            }
        } catch (e: Exception) {
            logger.debug("Failed tagging exception occured")
            val status = when (e) {
                is BadRequestException -> HttpStatus.BAD_REQUEST
                else -> HttpStatus.INTERNAL_SERVER_ERROR
            }
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
    ): AuroraResponse<ImageTagResource> = runBlocking(MDCContext() + threadPoolContext) {
        val watch = StopWatch().apply { this.start() }
        val response = tagUrlsWrapper.tagUrls
            .map {
                async(Dispatchers.Default) {
                    runCatching {
                        val imageRepoCommand = imageRepoCommandAssembler.createAndValidateCommand(it, bearerToken)
                        require(imageRepoCommand.imageTag != null) {
                            "ImageRepo with spec=${imageRepoCommand.fullRepoCommand} does not contain a tag"
                        }

                        imageTagResourceAssembler.toImageTagResource(
                            manifestDto = dockerRegistryService.getImageManifestInformation(imageRepoCommand),
                            requestUrl = imageRepoCommand.fullRepoCommand
                        )
                    }.recoverCatching { ex ->
                        throw RequestResultException(repoUrl = it, cause = ex)
                    }
                }
            }.map { it.await() }

        imageTagResourceAssembler.toAuroraResponse(response).also {
            watch.stop()
            logger.debug {
                "Get imageManifest tookMs=${watch.totalTimeMillis} urls=${tagUrlsWrapper.tagUrls.joinToString(
                    ","
                )}"
            }
        }
    }

    @GetMapping("/tags")
    fun getImageTags(
        @RequestParam repoUrl: String,
        @RequestParam filter: String?,
        @RequestHeader(required = false, value = HttpHeaders.AUTHORIZATION) bearerToken: String?
    ): AuroraResponse<TagResource> = runBlocking(MDCContext() + threadPoolContext) {
        val watch = StopWatch().apply { this.start() }
        val response = runCatching {
            val imageRepoCommand = imageRepoCommandAssembler.createAndValidateCommand(repoUrl, bearerToken)
            imageTagResourceAssembler.toTagResource(
                dockerRegistryService.getImageTags(imageRepoCommand, filter)
            )
        }.recoverCatching {
            throw RequestResultException(repoUrl, it)
        }
        imageTagResourceAssembler.toAuroraResponse(response).also {
            watch.stop()
            logger.debug { "Get imageTags tookMs=${watch.totalTimeMillis} url=$repoUrl" }
        }
    }
}

@Component
class ImageTagResourceAssembler(val auroraResponseAssembler: AuroraResponseAssembler) {
    fun toAuroraResponse(resources: Result<List<TagResource>>): AuroraResponse<TagResource> =
        auroraResponseAssembler.toAuroraResponse(resources)

    fun toAuroraResponse(resources: List<Result<ImageTagResource>>): AuroraResponse<ImageTagResource> =
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
