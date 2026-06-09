package com.maluca.policy;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.maluca.config.MalucaProperties;

import reactor.core.publisher.Mono;

/**
 * Minimal policy admin API, guarded by a bearer-style token header.
 * GET lists the active (compiled) policies; POST /reload forces a reload
 * from disk — useful when the file watcher can't see the change (e.g.
 * volume mounts that don't propagate inotify).
 */
@RestController
@RequestMapping("/_maluca/admin/policies")
public class PolicyAdminController {

    private static final String TOKEN_HEADER = "X-Maluca-Admin-Token";

    private final PolicyRegistry registry;
    private final String adminToken;

    public PolicyAdminController(PolicyRegistry registry, MalucaProperties properties) {
        this.registry = registry;
        this.adminToken = properties.adminToken();
    }

    @GetMapping
    public Mono<ResponseEntity<Object>> list(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        if (unauthorized(token)) {
            return Mono.just(deny());
        }
        List<Map<String, Object>> policies = registry.snapshot().stream()
                .<Map<String, Object>>map(p -> Map.of(
                        "name", p.name(),
                        "route", p.pattern().getPatternString(),
                        "mode", p.mode().name(),
                        "tiers", p.tiers(),
                        "rateLimit", p.rateLimit() == null ? "global-default" : p.rateLimit().toString(),
                        "failMode", p.failMode().name()))
                .toList();
        return Mono.just(ResponseEntity.ok(Map.of("policies", policies)));
    }

    @PostMapping("/reload")
    public Mono<ResponseEntity<Object>> reload(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        if (unauthorized(token)) {
            return Mono.just(deny());
        }
        boolean ok = registry.load();
        return Mono.just(ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("reloaded", ok, "activePolicies", registry.snapshot().size())));
    }

    private boolean unauthorized(String token) {
        return adminToken == null || adminToken.isBlank() || !adminToken.equals(token);
    }

    private static ResponseEntity<Object> deny() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "invalid or missing " + TOKEN_HEADER));
    }
}
