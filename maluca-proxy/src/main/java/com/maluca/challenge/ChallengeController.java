package com.maluca.challenge;

import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.maluca.identity.ClientIdentityExtractor;
import com.maluca.metrics.ChallengeMetrics;

import reactor.core.publisher.Mono;

/**
 * Challenge verification. On success the response sets the signed
 * {@code maluca_pass} cookie; its presence lets subsequent requests bypass
 * the scorer until the TTL lapses.
 */
@RestController
@RequestMapping("/_maluca/challenge")
public class ChallengeController {

    public record VerifyRequest(String token, String nonce) {
    }

    private final ChallengeService challengeService;
    private final ClientIdentityExtractor identityExtractor;
    private final ChallengeMetrics metrics;

    public ChallengeController(ChallengeService challengeService,
                               ClientIdentityExtractor identityExtractor,
                               ChallengeMetrics metrics) {
        this.challengeService = challengeService;
        this.identityExtractor = identityExtractor;
        this.metrics = metrics;
    }

    @PostMapping("/verify")
    public Mono<ResponseEntity<Map<String, String>>> verify(@RequestBody VerifyRequest body,
                                                            ServerWebExchange exchange) {
        String clientKey = identityExtractor.extract(exchange).compositeKey();
        return challengeService.verify(body.token(), body.nonce(), clientKey)
                .map(result -> switch (result) {
                    case ChallengeService.VerifyResult.Success success -> {
                        metrics.solved();
                        ResponseCookie cookie = ResponseCookie.from("maluca_pass", success.passCookieValue())
                                .httpOnly(true)
                                .sameSite("Lax")
                                .path("/")
                                .maxAge(Duration.ofSeconds(success.passTtlSeconds()))
                                .build();
                        exchange.getResponse().addCookie(cookie);
                        yield ResponseEntity.ok(Map.of("status", "ok"));
                    }
                    case ChallengeService.VerifyResult.Failure failure -> {
                        metrics.failed(failure.reason());
                        yield ResponseEntity.status(HttpStatus.FORBIDDEN)
                                .body(Map.of("status", "failed", "reason", failure.reason()));
                    }
                });
    }
}
