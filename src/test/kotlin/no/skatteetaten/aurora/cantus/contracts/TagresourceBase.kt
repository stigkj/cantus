package no.skatteetaten.aurora.cantus.contracts

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.given
import no.skatteetaten.aurora.cantus.controller.ImageTagResourceAssembler
import no.skatteetaten.aurora.cantus.service.DockerRegistryService
import no.skatteetaten.aurora.cantus.service.ImageTagTypedDto
import no.skatteetaten.aurora.cantus.service.ImageTagsWithTypeDto
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.mock.mockito.MockBean

open class TagresourceBase : ContractBase() {

    @MockBean
    private lateinit var dockerRegistryService: DockerRegistryService

    @MockBean
    private lateinit var resourceAssembler: ImageTagResourceAssembler

    @BeforeEach
    fun setUp() {
        withContractResponses(this) {
            given(dockerRegistryService.getImageTags(any())).willReturn(
                ImageTagsWithTypeDto(
                    listOf(
                        ImageTagTypedDto(
                            ""
                        )
                    )
                )
            )
            given(
                resourceAssembler.toResource(
                    any<ImageTagsWithTypeDto>(),
                    any()
                )
            ).willReturn(it.response("TagResource"))

            given(resourceAssembler.toGroupedResource(any(), any())).willReturn(it.response("GroupedTagResource"))
        }
    }
}