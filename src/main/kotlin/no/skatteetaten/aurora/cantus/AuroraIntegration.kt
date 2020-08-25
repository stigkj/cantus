package no.skatteetaten.aurora.cantus

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding

@ConfigurationProperties("integrations")
@ConstructorBinding
data class AuroraIntegration(
    val docker: Map<String, DockerRegistry>
) {
    enum class AuthType { None, Basic, Bearer }

    data class DockerRegistry(
        val url: String,
        val guiUrlPattern: String? = null,
        val auth: AuthType? = AuthType.None,
        val https: Boolean = true,
        val readOnly: Boolean = true,
        val enabled: Boolean = true
    )
}
