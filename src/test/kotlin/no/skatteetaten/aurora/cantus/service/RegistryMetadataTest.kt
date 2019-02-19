package no.skatteetaten.aurora.cantus.service

import assertk.assert
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class RegistryMetadataTest {

    private val metadataResolver = RegistryMetadataResolver(listOf("docker-registry.default.svc:5000"))

    @Test
    fun `verify metadata for internal registry`() {

        val metadata = metadataResolver.getMetadataForRegistry("docker-registry.default.svc:5000")

        assert(metadata.apiSchema).isEqualTo("http")
        assert(metadata.authenticationMethod).isEqualTo(AuthenticationMethod.KUBERNETES_TOKEN)
        assert(metadata.isInternal).isEqualTo(true)
    }

    @Test
    fun `verify metadata for central registry`() {

        val metadata = metadataResolver.getMetadataForRegistry("docker-registry.somesuch.no:5000")

        assert(metadata.apiSchema).isEqualTo("https")
        assert(metadata.authenticationMethod).isEqualTo(AuthenticationMethod.NONE)
    }

    @Test
    fun `verify metadata for internal IP`() {
        val metadata = metadataResolver.getMetadataForRegistry("127.0.0.1:5000")

        assert(metadata.apiSchema).isEqualTo("http")
        assert(metadata.authenticationMethod).isEqualTo(AuthenticationMethod.KUBERNETES_TOKEN)
        assert(metadata.isInternal).isEqualTo(true)
    }
}