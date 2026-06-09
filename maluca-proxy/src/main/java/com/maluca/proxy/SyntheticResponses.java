package com.maluca.proxy;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/** Responses Maluca generates itself (429/403/502/challenge pages). */
public final class SyntheticResponses {

    private SyntheticResponses() {
    }

    public static Mono<Void> tooManyRequests(ServerWebExchange exchange, long retryAfterSeconds) {
        exchange.getResponse().getHeaders().set("Retry-After", String.valueOf(Math.max(retryAfterSeconds, 1)));
        return json(exchange, HttpStatus.TOO_MANY_REQUESTS,
                "{\"error\":\"rate_limited\",\"message\":\"Too many requests. Slow down.\"}");
    }

    public static Mono<Void> blocked(ServerWebExchange exchange) {
        return json(exchange, HttpStatus.FORBIDDEN,
                "{\"error\":\"blocked\",\"message\":\"Request blocked by Maluca.\"}");
    }

    public static Mono<Void> badGateway(ServerWebExchange exchange) {
        return json(exchange, HttpStatus.BAD_GATEWAY,
                "{\"error\":\"bad_gateway\",\"message\":\"Upstream unavailable.\"}");
    }

    public static Mono<Void> json(ServerWebExchange exchange, HttpStatus status, String body) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    public static Mono<Void> html(ServerWebExchange exchange, HttpStatus status, String body) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.TEXT_HTML);
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
