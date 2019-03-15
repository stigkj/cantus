package no.skatteetaten.aurora.cantus.contracts

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.given
import no.skatteetaten.aurora.cantus.controller.ImageTagResourceAssembler
import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import no.skatteetaten.aurora.cantus.service.ImageTagTypedDto
import no.skatteetaten.aurora.cantus.service.ImageTagsWithTypeDto
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.mock.mockito.MockBean

class TagresourceBase : ContractBase() {

    @MockBean
    private lateinit var dockerRegistryService: DockerRegistryService

    @MockBean
    private lateinit var imageTagResourceAssembler: ImageTagResourceAssembler

    @BeforeEach
    fun setUp() {
        withContractResponses(this) {
            given(dockerRegistryService.getImageTags(any()))
                .willReturn(ImageTagsWithTypeDto(listOf(ImageTagTypedDto(""))))

            given(imageTagResourceAssembler.groupedTagResourceToAuroraResponse(any()))
                .willReturn(it.response("GroupedTagResource"))

            given(imageTagResourceAssembler.tagResourceToAuroraResponse(any()))
                .willReturn(it.response("TagResource"))
        }
    }
}