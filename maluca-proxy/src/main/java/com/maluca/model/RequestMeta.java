package com.maluca.model;

import java.util.List;

import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * The request attributes the scoring pipeline is allowed to see. Extracted
 * once so collectors/scorers stay pure and trivially unit-testable.
 */
public record RequestMeta(
        String method,
        String path,
        String userAgent,
        String accept,
        String acceptLanguage,
        String acceptEncoding,
        List<String> headerOrder,
        String tlsFingerprint) {

    public static RequestMeta from(ServerHttpRequest request) {
        var headers = request.getHeaders();
        return new RequestMeta(
                request.getMethod().name(),
                request.getURI().getRawPath(),
                headers.getFirst("User-Agent"),
                headers.getFirst("Accept"),
                headers.getFirst("Accept-Language"),
                headers.getFirst("Accept-Encoding"),
                List.copyOf(headers.keySet()),
                headers.getFirst("X-TLS-JA3"));
    }
}
