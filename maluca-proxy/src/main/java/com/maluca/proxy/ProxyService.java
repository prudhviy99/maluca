package com.maluca.proxy;

import java.net.URI;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;

import com.maluca.config.MalucaProperties;
import com.maluca.metrics.MalucaMetrics;
import com.maluca.metrics.Observed;
import com.maluca.model.ClientIdentity;
import com.maluca.state.ClientStateRepository;

import io.micrometer.observation.ObservationRegistry;
import reactor.core.publisher.Mono;

/**
 * Streams a request to the upstream and the response back to the client.
 * Bodies are never buffered in full — DataBuffers flow through as they
 * arrive, so large payloads cost constant memory.
 */
@Service
public class ProxyService {

    private static final Logger log = LoggerFactory.getLogger(ProxyService.class);

    /** RFC 9110 connection-scoped headers — must not be forwarded by proxies. */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "host");

    private final WebClient webClient;
    private final URI upstreamBase;
    private final MalucaMetrics metrics;
    private final ClientStateRepository stateRepository;
    private final ObservationRegistry observations;

    public ProxyService(WebClient upstreamWebClient,
                        MalucaProperties properties,
                        MalucaMetrics metrics,
                        ClientStateRepository stateRepository,
                        ObservationRegistry observations) {
        this.webClient = upstreamWebClient;
        this.upstreamBase = URI.create(properties.upstream().url());
        this.metrics = metrics;
        this.stateRepository = stateRepository;
        this.observations = observations;
    }

    public Mono<Void> forward(ServerWebExchange exchange, ClientIdentity identity) {
        return Observed.mono(observations, "maluca.upstream", doForward(exchange, identity));
    }

    private Mono<Void> doForward(ServerWebExchange exchange, ClientIdentity identity) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();

        URI target = buildTargetUri(request);
        long startNanos = System.nanoTime();

        return webClient
                .method(HttpMethod.valueOf(request.getMethod().name()))
                .uri(target)
                .headers(headers -> copyRequestHeaders(request, headers, identity))
                .body(BodyInserters.fromDataBuffers(request.getBody()))
                .exchangeToMono(upstreamResponse -> {
                    metrics.recordUpstreamLatency(System.nanoTime() - startNanos);

                    HttpStatusCode status = upstreamResponse.statusCode();
                    response.setStatusCode(status);
                    copyResponseHeaders(upstreamResponse.headers().asHttpHeaders(), response.getHeaders());

                    if (status.is4xxClientError()) {
                        // fire-and-forget: 4xx ratio is a scoring signal, not on the hot path
                        stateRepository.recordUpstream4xx(identity.compositeKey())
                                .onErrorResume(e -> Mono.empty())
                                .subscribe();
                    }

                    return response.writeWith(upstreamResponse.bodyToFlux(DataBuffer.class));
                })
                .onErrorResume(error -> {
                    log.warn("upstream_error target={} error={}", target, error.toString());
                    metrics.incrementUpstreamErrors();
                    return SyntheticResponses.badGateway(exchange);
                });
    }

    private URI buildTargetUri(ServerHttpRequest request) {
        String rawQuery = request.getURI().getRawQuery();
        String pathAndQuery = request.getURI().getRawPath()
                + (rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery);
        return URI.create(upstreamBase.toString() + pathAndQuery);
    }

    private void copyRequestHeaders(ServerHttpRequest request, HttpHeaders target, ClientIdentity identity) {
        request.getHeaders().forEach((name, values) -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                target.put(name, values);
            }
        });
        // Standard forwarding headers so the upstream can see the real client
        target.add("X-Forwarded-For", identity.ip());
        target.set("X-Forwarded-Proto", request.getURI().getScheme());
        String hostHeader = request.getHeaders().getFirst(HttpHeaders.HOST);
        if (hostHeader != null) {
            target.set("X-Forwarded-Host", hostHeader);
        }
    }

    private void copyResponseHeaders(HttpHeaders source, HttpHeaders target) {
        source.forEach((name, values) -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                target.put(name, values);
            }
        });
    }
}
