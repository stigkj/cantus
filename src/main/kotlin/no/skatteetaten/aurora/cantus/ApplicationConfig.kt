package no.skatteetaten.aurora.cantus

import io.netty.channel.ChannelOption
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.timeout.ReadTimeoutHandler
import no.skatteetaten.aurora.cantus.controller.ImageTagResourceAssembler
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.toMono
import reactor.netty.http.client.HttpClient
import reactor.netty.tcp.SslProvider
import reactor.netty.tcp.TcpClient
import java.util.concurrent.TimeUnit
import kotlin.math.min

@Configuration
class ApplicationConfig {

    private val logger = LoggerFactory.getLogger(ApplicationConfig::class.java)

    @Bean
    fun imageTagResourceAssembler() = ImageTagResourceAssembler()

    @Bean
    fun webClient() = webClientBuilder().build()

    fun webClientBuilder(): WebClient.Builder =
        WebClient
            .builder()
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .exchangeStrategies(exchangeStrategies())
            .filter(ExchangeFilterFunction.ofRequestProcessor {
                val bearer = it.headers()[HttpHeaders.AUTHORIZATION]?.firstOrNull()?.let { token ->
                    val t = token.substring(0, min(token.length, 11)).replace("Bearer", "")
                    "bearer=$t"
                } ?: ""
                logger.debug("HttpRequest method=${it.method()} url=${it.url()} $bearer")
                it.toMono()
            })
            .clientConnector(clientConnector())

    private fun exchangeStrategies(): ExchangeStrategies {
        val objectMapper = createObjectMapper()

        return ExchangeStrategies
            .builder()
            .codecs {
                it.defaultCodecs().jackson2JsonDecoder(
                    Jackson2JsonDecoder(
                        objectMapper,
                        MediaType.valueOf("application/vnd.docker.distribution.manifest.v1+json"),
                        MediaType.valueOf("application/vnd.docker.distribution.manifest.v1+prettyjws"),
                        MediaType.valueOf("application/vnd.docker.distribution.manifest.v2+json"),
                        MediaType.valueOf("application/vnd.docker.container.image.v1+json"),
                        MediaType.valueOf("application/json")
                    )
                )
                it.defaultCodecs().jackson2JsonEncoder(
                    Jackson2JsonEncoder(
                        objectMapper,
                        MediaType.valueOf("application/json")
                    )
                )
            }
            .build()
    }

    private fun clientConnector(): ReactorClientHttpConnector {

        val sslProvider = SslProvider.builder().sslContext(
            SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
        ).defaultConfiguration(SslProvider.DefaultConfigurationType.NONE).build()

        val tcpClient = TcpClient.create()
            .secure(sslProvider)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
            .doOnConnected { connection ->
                connection.addHandlerLast(ReadTimeoutHandler(30000.toLong(), TimeUnit.MILLISECONDS))
            }

        val httpClient = HttpClient.from(tcpClient)
        httpClient.compress(true)

        return ReactorClientHttpConnector(httpClient)
    }
}
