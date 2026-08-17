package com.maluca.policy;

import java.util.LinkedHashMap;
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
import com.maluca.model.RateLimitConfig;

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
                .map(PolicyAdminController::describe)
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

    private static Map<String, Object> describe(CompiledPolicy policy) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", policy.name());
        view.put("route", policy.pattern().getPatternString());
        view.put("mode", policy.mode().name());
        view.put("tiers", policy.tiers());
        view.put("keying", policy.keying() == null ? null : policy.keying().name());
        view.put("bands", describeBands(policy.bands()));
        view.put("rateLimit", describeRateLimit(policy.rateLimit()));
        view.put("allowlist", policy.allowlist().specs());
        view.put("denylist", policy.denylist().specs());
        view.put("failMode", policy.failMode().name());
        return view;
    }

    private static Map<String, Object> describeBands(MalucaProperties.Bands bands) {
        if (bands == null) {
            return Map.of();
        }
        return Map.of(
                "observeMin", bands.observeMin(),
                "softLimitMin", bands.softLimitMin(),
                "hardLimitMin", bands.hardLimitMin(),
                "challengeMin", bands.challengeMin(),
                "blockMin", bands.blockMin());
    }

    private static Map<String, Object> describeRateLimit(RateLimitConfig rateLimit) {
        if (rateLimit == null) {
            return Map.of();
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("algorithm", rateLimit.algorithm() == null ? null : rateLimit.algorithm().name());
        view.put("limit", rateLimit.limit());
        view.put("windowSeconds", rateLimit.windowSeconds());
        view.put("ratePerSecond", rateLimit.ratePerSecond());
        view.put("burst", rateLimit.burst());
        return view;
    }

    private static ResponseEntity<Object> deny() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "invalid or missing " + TOKEN_HEADER));
    }
}
