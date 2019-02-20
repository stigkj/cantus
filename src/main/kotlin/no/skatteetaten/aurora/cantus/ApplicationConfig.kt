package no.skatteetaten.aurora.cantus

import io.netty.channel.ChannelOption
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
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

private val logger = KotlinLogging.logger {}

@Configuration
class ApplicationConfig {

    @Bean
    fun webClient(builder: WebClient.Builder, tcpClient: TcpClient) =
        builder
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .exchangeStrategies(exchangeStrategies())
            .filter(ExchangeFilterFunction.ofRequestProcessor {
                val bearer = it.headers()[HttpHeaders.AUTHORIZATION]?.firstOrNull()?.let { token ->
                    val t = token.substring(0, min(token.length, 11)).replace("Bearer", "")
                    "bearer=$t"
                } ?: ""
                logger.debug("HttpRequest method=${it.method()} url=${it.url()} token=$bearer")
                it.toMono()
            })
            .clientConnector(ReactorClientHttpConnector(HttpClient.from(tcpClient).compress(true))).build()

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

    @Bean
    fun tcpClient(
        @Value("\${cantus.httpclient.readTimeout:5000}") readTimeout: Long,
        @Value("\${cantus.httpclient.writeTimeout:5000}") writeTimeout: Long,
        @Value("\${cantus.httpclient.connectTimeout:5000}") connectTimeout: Int
    ): TcpClient {
        val sslProvider = SslProvider.builder().sslContext(
            SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
        ).defaultConfiguration(SslProvider.DefaultConfigurationType.NONE).build()

        return TcpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout)
            .secure(sslProvider)
            .doOnConnected { connection ->
                connection
                    .addHandlerLast(ReadTimeoutHandler(readTimeout, TimeUnit.MILLISECONDS))
                    .addHandlerLast(WriteTimeoutHandler(writeTimeout, TimeUnit.MILLISECONDS))
            }
    }
}
