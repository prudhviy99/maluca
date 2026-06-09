package com.maluca.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
public class WebClientConfig {

    /**
     * The upstream-facing client. Connection pooling + keep-alive matter here:
     * the proxy multiplexes many client connections over few upstream ones.
     */
    @Bean
    public WebClient upstreamWebClient(MalucaProperties properties) {
        MalucaProperties.Upstream upstream = properties.upstream();

        ConnectionProvider pool = ConnectionProvider.builder("maluca-upstream")
                .maxConnections(upstream.maxConnections())
                .pendingAcquireTimeout(Duration.ofSeconds(5))
                .build();

        HttpClient httpClient = HttpClient.create(pool)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, upstream.connectTimeoutMs())
                .responseTimeout(Duration.ofMillis(upstream.responseTimeoutMs()))
                .compress(false);

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
