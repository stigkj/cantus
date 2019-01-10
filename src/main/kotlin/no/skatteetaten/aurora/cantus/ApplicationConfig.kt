package no.skatteetaten.aurora.cantus

import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ApplicationConfig {
    @Bean
    fun restTemplate(restTemplateBuilder: RestTemplateBuilder) = restTemplateBuilder.build()
}
