package no.skatteetaten.aurora.cantus.service

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class RegistryMetadataTest {

    private val metadataResolver = RegistryMetadataResolver(listOf("docker-registry.default.svc:5000"))

    @Test
    fun `verify metadata for internal registry`() {

        val metadata = metadataResolver.getMetadataForRegistry("docker-registry.default.svc:5000")

        assertThat(metadata.apiSchema).isEqualTo("http")
        assertThat(metadata.authenticationMethod).isEqualTo(AuthenticationMethod.KUBERNETES_TOKEN)
        assertThat(metadata.isInternal).isEqualTo(true)
    }

    @Test
    fun `verify metadata for central registry`() {

        val metadata = metadataResolver.getMetadataForRegistry("docker-registry.somesuch.no:5000")

        assertThat(metadata.apiSchema).isEqualTo("https")
        assertThat(metadata.authenticationMethod).isEqualTo(AuthenticationMethod.NONE)
    }

    @Test
    fun `verify metadata for internal IP`() {
        val metadata = metadataResolver.getMetadataForRegistry("127.0.0.1:5000")

        assertThat(metadata.apiSchema).isEqualTo("http")
        assertThat(metadata.authenticationMethod).isEqualTo(AuthenticationMethod.KUBERNETES_TOKEN)
        assertThat(metadata.isInternal).isEqualTo(true)
    }
}