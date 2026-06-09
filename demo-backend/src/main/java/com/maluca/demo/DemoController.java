package com.maluca.demo;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * A small demo application that sits behind Maluca. Routes are chosen to give the
 * proxy realistic traffic shapes: cheap pages, an expensive search, a sensitive
 * login endpoint, and a catalog that scrapers love to enumerate.
 */
@RestController
public class DemoController {

    private static final List<Map<String, Object>> PRODUCTS = IntStream.rangeClosed(1, 100)
            .<Map<String, Object>>mapToObj(i -> Map.of(
                    "id", i,
                    "name", "Product " + i,
                    "price", 9.99 + i))
            .toList();

    @GetMapping("/")
    public Mono<Map<String, Object>> home() {
        return Mono.just(Map.of(
                "service", "demo-backend",
                "message", "Hello from behind Maluca",
                "time", Instant.now().toString()));
    }

    @GetMapping("/health")
    public Mono<Map<String, String>> health() {
        return Mono.just(Map.of("status", "UP"));
    }

    @GetMapping("/api/products")
    public Mono<List<Map<String, Object>>> products(
            @RequestParam(name = "page", defaultValue = "0") int page) {
        int from = Math.min(Math.max(page, 0) * 10, PRODUCTS.size());
        int to = Math.min(from + 10, PRODUCTS.size());
        return Mono.just(PRODUCTS.subList(from, to));
    }

    @GetMapping("/api/products/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> product(@PathVariable int id) {
        if (id < 1 || id > PRODUCTS.size()) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        return Mono.just(ResponseEntity.ok(PRODUCTS.get(id - 1)));
    }

    /** Simulated expensive endpoint — adds 30-80ms of "work". */
    @GetMapping("/search")
    public Mono<Map<String, Object>> search(@RequestParam(name = "q", defaultValue = "") String q) {
        long workMs = ThreadLocalRandom.current().nextLong(30, 80);
        return Mono.delay(Duration.ofMillis(workMs))
                .map(t -> Map.of(
                        "query", q,
                        "results", PRODUCTS.stream()
                                .filter(p -> q.isBlank() || p.get("name").toString().toLowerCase().contains(q.toLowerCase()))
                                .limit(5)
                                .toList(),
                        "tookMs", workMs));
    }

    /** Sensitive endpoint — accepts any user, "authenticates" demo/demo only. */
    @PostMapping("/login")
    public Mono<ResponseEntity<Map<String, Object>>> login(
            @RequestBody(required = false) Map<String, String> body) {
        String user = body == null ? null : body.get("username");
        String pass = body == null ? null : body.get("password");
        if ("demo".equals(user) && "demo".equals(pass)) {
            return Mono.just(ResponseEntity.ok(Map.of("status", "ok", "token", "demo-session-token")));
        }
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("status", "invalid_credentials")));
    }

    @PostMapping("/api/checkout")
    public Mono<Map<String, Object>> checkout(@RequestBody(required = false) Map<String, Object> body) {
        return Mono.just(Map.of("status", "ok", "orderId", ThreadLocalRandom.current().nextInt(100000)));
    }

    @GetMapping("/admin")
    public Mono<ResponseEntity<Map<String, String>>> admin() {
        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "admin area requires authentication")));
    }

    /** Echoes request headers — handy for verifying what the proxy forwards. */
    @GetMapping("/echo")
    public Mono<Map<String, String>> echo(@RequestHeader Map<String, String> headers) {
        return Mono.just(headers);
    }
}
