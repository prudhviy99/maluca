package com.maluca.triage.config;

import java.net.http.HttpClient;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class UpstreamConfiguration {

    @Bean
    RestClient malucaProxyClient(RestClient.Builder builder, TriageProperties properties) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.upstreams().proxyConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.upstreams().proxyReadTimeout());
        return builder.clone().baseUrl(properties.upstreams().proxyBaseUrl())
                .defaultHeader("X-Maluca-Admin-Token", properties.upstreams().proxyAdminToken())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    @Qualifier("prometheusRestClient")
    RestClient prometheusRestClient(RestClient.Builder builder, TriageProperties properties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(java.time.Duration.ofSeconds(2));
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(5));
        return builder.clone().baseUrl(properties.upstreams().prometheusBaseUrl())
                .requestFactory(requestFactory).build();
    }
}
