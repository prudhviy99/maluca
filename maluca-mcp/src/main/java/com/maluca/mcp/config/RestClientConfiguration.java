package com.maluca.mcp.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class RestClientConfiguration {

    public static final String INTERNAL_TOKEN_HEADER = "X-Maluca-Internal-Token";
    public static final String ADMIN_TOKEN_HEADER = "X-Maluca-Admin-Token";

    @Bean
    @Qualifier("triageRestClient")
    RestClient triageRestClient(MalucaMcpProperties properties) {
        return build(properties.triage(), INTERNAL_TOKEN_HEADER, properties.triage().authToken());
    }

    @Bean
    @Qualifier("triageApprovalRestClient")
    RestClient triageApprovalRestClient(MalucaMcpProperties properties) {
        String token = properties.triageApprovalToken();
        return build(properties.triage(), HttpHeaders.AUTHORIZATION,
                token == null || token.isBlank() ? "" : "Bearer " + token);
    }

    @Bean
    @Qualifier("proxyRestClient")
    RestClient proxyRestClient(MalucaMcpProperties properties) {
        return build(properties.proxy(), ADMIN_TOKEN_HEADER, properties.proxy().authToken());
    }

    @Bean
    @Qualifier("prometheusRestClient")
    RestClient prometheusRestClient(MalucaMcpProperties properties) {
        String token = properties.prometheus().authToken();
        return build(properties.prometheus(), HttpHeaders.AUTHORIZATION,
                token == null || token.isBlank() ? "" : "Bearer " + token);
    }

    private static RestClient build(
            MalucaMcpProperties.Upstream upstream,
            String header,
            String token) {
        validateBaseUrl(upstream);
        validateTimeout(upstream.connectTimeout());
        validateTimeout(upstream.readTimeout());
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(upstream.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(upstream.readTimeout());

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(upstream.baseUrl().toString())
                .requestFactory(requestFactory);
        if (token != null && !token.isBlank()) {
            builder.defaultHeader(header, token);
        }
        return builder.build();
    }

    private static void validateTimeout(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("upstream timeout must be positive");
        }
    }

    private static void validateBaseUrl(MalucaMcpProperties.Upstream upstream) {
        String scheme = upstream.baseUrl().getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("upstream base-url must use http or https");
        }
        if (upstream.baseUrl().getHost() == null) {
            throw new IllegalArgumentException("upstream base-url must be absolute");
        }
        if (upstream.baseUrl().getUserInfo() != null
                || upstream.baseUrl().getQuery() != null
                || upstream.baseUrl().getFragment() != null) {
            throw new IllegalArgumentException("upstream base-url cannot contain user info, query, or fragment");
        }
        String path = upstream.baseUrl().getPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw new IllegalArgumentException("upstream base-url must not contain a path");
        }
    }
}
