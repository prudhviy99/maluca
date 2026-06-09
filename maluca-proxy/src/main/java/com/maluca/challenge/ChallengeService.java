package com.maluca.challenge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import org.springframework.stereotype.Service;

import com.maluca.config.MalucaProperties;
import com.maluca.state.ClientStateRepository;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import reactor.core.publisher.Mono;

/**
 * Proof-of-work (SHA-256 hashcash) and JS-lite challenges.
 *
 * <p>Challenge token payload: {@code clientKey|issuedAtEpochSec|difficultyBits|randomHex|type}.
 * The token is HMAC-signed and short-TTL. Solving means finding a nonce such
 * that {@code SHA-256(token + ":" + nonce)} has at least {@code difficultyBits}
 * leading zero bits. Verification recomputes one hash — the asymmetry (client
 * does 2^difficulty work, server does 1 hash) is the whole point.
 *
 * <p>Solved challenges are one-time use: the token's random component is
 * claimed in Redis with SET NX before a pass is issued (replay protection).
 */
@Service
public class ChallengeService {

    public enum ChallengeType { POW, JS_LITE }

    public record IssuedChallenge(String token, ChallengeType type, int difficultyBits) {
    }

    public sealed interface VerifyResult {
        record Success(String passCookieValue, long passTtlSeconds) implements VerifyResult {}
        record Failure(String reason) implements VerifyResult {}
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final HmacSigner signer;
    private final MalucaProperties.Challenge cfg;
    private final ReactiveStringRedisTemplate redis;

    public ChallengeService(MalucaProperties properties, ReactiveStringRedisTemplate redis) {
        this.cfg = properties.challenge();
        this.signer = new HmacSigner(cfg.secret());
        this.redis = redis;
    }

    // ── Issuance ──────────────────────────────────────────────────────────────

    public IssuedChallenge issue(String clientKey, int score) {
        ChallengeType type = score <= cfg.jsLiteMaxScore() ? ChallengeType.JS_LITE : ChallengeType.POW;
        int difficulty = type == ChallengeType.JS_LITE ? 0 : adaptiveDifficulty(score, 0);
        return issue(clientKey, type, difficulty);
    }

    public IssuedChallenge issue(String clientKey, ChallengeType type, int difficultyBits) {
        byte[] rand = new byte[16];
        RANDOM.nextBytes(rand);
        String payload = String.join("|",
                clientKey,
                String.valueOf(Instant.now().getEpochSecond()),
                String.valueOf(difficultyBits),
                HexFormat.of().formatHex(rand),
                type.name());
        return new IssuedChallenge(signer.sign(payload), type, difficultyBits);
    }

    /**
     * Difficulty scales with how far past the challenge band the client is,
     * plus an extra bump for datacenter-origin clients (Phase 5 signal).
     * Each +1 bit doubles the client's expected work; verification cost is
     * constant.
     */
    int adaptiveDifficulty(int score, int datacenterBump) {
        int scoreBump = Math.max(0, (score - 75) / 5);
        return Math.min(cfg.baseDifficultyBits() + scoreBump + datacenterBump, cfg.maxDifficultyBits());
    }

    // ── Verification ──────────────────────────────────────────────────────────

    public Mono<VerifyResult> verify(String token, String nonce, String clientKey) {
        String payload = signer.verify(token);
        if (payload == null) {
            return Mono.just(new VerifyResult.Failure("bad_signature"));
        }
        String[] parts = payload.split("\\|");
        if (parts.length != 5) {
            return Mono.just(new VerifyResult.Failure("bad_payload"));
        }
        String issuedTo = parts[0];
        long issuedAt = Long.parseLong(parts[1]);
        int difficulty = Integer.parseInt(parts[2]);
        String challengeId = parts[3];
        ChallengeType type = ChallengeType.valueOf(parts[4]);

        if (!issuedTo.equals(clientKey)) {
            return Mono.just(new VerifyResult.Failure("client_mismatch"));
        }
        if (Instant.now().getEpochSecond() - issuedAt > cfg.challengeTtlSeconds()) {
            return Mono.just(new VerifyResult.Failure("expired"));
        }
        if (type == ChallengeType.POW && !powSolved(token, nonce, difficulty)) {
            return Mono.just(new VerifyResult.Failure("bad_pow"));
        }

        // one-time use: claim the challenge id atomically
        String replayKey = ClientStateRepository.PREFIX + "chal:used:" + challengeId;
        return redis.opsForValue()
                .setIfAbsent(replayKey, "1", Duration.ofSeconds(cfg.challengeTtlSeconds() * 2))
                .map(claimed -> Boolean.TRUE.equals(claimed)
                        ? new VerifyResult.Success(issuePass(clientKey), cfg.passTtlSeconds())
                        : new VerifyResult.Failure("replayed"));
    }

    boolean powSolved(String token, String nonce, int difficultyBits) {
        if (nonce == null || nonce.isBlank() || nonce.length() > 64) {
            return false;
        }
        byte[] digest = sha256(token + ":" + nonce);
        return leadingZeroBits(digest) >= difficultyBits;
    }

    static int leadingZeroBits(byte[] bytes) {
        int bits = 0;
        for (byte b : bytes) {
            if (b == 0) {
                bits += 8;
                continue;
            }
            bits += Integer.numberOfLeadingZeros(b & 0xFF) - 24;
            break;
        }
        return bits;
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Pass cookies ──────────────────────────────────────────────────────────

    public String issuePass(String clientKey) {
        long expiry = Instant.now().getEpochSecond() + cfg.passTtlSeconds();
        return signer.sign(clientKey + "|" + expiry);
    }

    /** True if the cookie is validly signed, unexpired, and bound to this client. */
    public boolean isValidPass(String cookieValue, String clientKey) {
        String payload = signer.verify(cookieValue);
        if (payload == null) {
            return false;
        }
        int sep = payload.lastIndexOf('|');
        if (sep <= 0) {
            return false;
        }
        try {
            String boundTo = payload.substring(0, sep);
            long expiry = Long.parseLong(payload.substring(sep + 1));
            return boundTo.equals(clientKey) && Instant.now().getEpochSecond() < expiry;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public long passTtlSeconds() {
        return cfg.passTtlSeconds();
    }

    public String renderPage(IssuedChallenge challenge) {
        return challenge.type() == ChallengeType.POW
                ? ChallengePages.pow(challenge.token(), challenge.difficultyBits())
                : ChallengePages.jsLite(challenge.token());
    }
}
