package com.maluca.challenge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import com.maluca.TestFixtures;

class ChallengeServiceTest {

    private static LettuceConnectionFactory factory;
    private static ReactiveStringRedisTemplate redis;
    private static ChallengeService service;

    @BeforeAll
    static void setUp() {
        assumeTrue(redisReachable(), "Redis not reachable — skipping");
        factory = new LettuceConnectionFactory(
                System.getenv().getOrDefault("REDIS_HOST", "localhost"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379")));
        factory.afterPropertiesSet();
        factory.start();
        redis = new ReactiveStringRedisTemplate(factory);
        service = new ChallengeService(TestFixtures.defaultProperties(), redis);
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.destroy();
        }
    }

    private static boolean redisReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(
                    System.getenv().getOrDefault("REDIS_HOST", "localhost"),
                    Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"))), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Brute-forces a nonce the same way the browser JS does. */
    private static String solve(String token, int difficultyBits) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (long nonce = 0; ; nonce++) {
            byte[] digest = sha.digest((token + ":" + nonce).getBytes(StandardCharsets.UTF_8));
            if (ChallengeService.leadingZeroBits(digest) >= difficultyBits) {
                return String.valueOf(nonce);
            }
        }
    }

    @Test
    void solvedPowChallengeYieldsPassCookie() throws Exception {
        var challenge = service.issue("1.2.3.4", ChallengeService.ChallengeType.POW, 8);
        String nonce = solve(challenge.token(), 8);

        var result = service.verify(challenge.token(), nonce, "1.2.3.4").block(Duration.ofSeconds(5));

        assertThat(result).isInstanceOf(ChallengeService.VerifyResult.Success.class);
        String cookie = ((ChallengeService.VerifyResult.Success) result).passCookieValue();
        assertThat(service.isValidPass(cookie, "1.2.3.4")).isTrue();
        assertThat(service.isValidPass(cookie, "5.6.7.8")).as("pass is client-bound").isFalse();
    }

    @Test
    void wrongNonceFailsPow() {
        var challenge = service.issue("1.2.3.4", ChallengeService.ChallengeType.POW, 20);

        var result = service.verify(challenge.token(), "0", "1.2.3.4").block(Duration.ofSeconds(5));

        // difficulty 20 means nonce "0" is essentially never a solution
        assertThat(result).isInstanceOf(ChallengeService.VerifyResult.Failure.class);
        assertThat(((ChallengeService.VerifyResult.Failure) result).reason()).isEqualTo("bad_pow");
    }

    @Test
    void replayedSolutionIsRejected() throws Exception {
        var challenge = service.issue("1.2.3.4", ChallengeService.ChallengeType.POW, 8);
        String nonce = solve(challenge.token(), 8);

        var first = service.verify(challenge.token(), nonce, "1.2.3.4").block(Duration.ofSeconds(5));
        var second = service.verify(challenge.token(), nonce, "1.2.3.4").block(Duration.ofSeconds(5));

        assertThat(first).isInstanceOf(ChallengeService.VerifyResult.Success.class);
        assertThat(second).isInstanceOf(ChallengeService.VerifyResult.Failure.class);
        assertThat(((ChallengeService.VerifyResult.Failure) second).reason()).isEqualTo("replayed");
    }

    @Test
    void challengeIsBoundToClient() throws Exception {
        var challenge = service.issue("1.2.3.4", ChallengeService.ChallengeType.POW, 8);
        String nonce = solve(challenge.token(), 8);

        var result = service.verify(challenge.token(), nonce, "9.9.9.9").block(Duration.ofSeconds(5));

        assertThat(result).isInstanceOf(ChallengeService.VerifyResult.Failure.class);
        assertThat(((ChallengeService.VerifyResult.Failure) result).reason()).isEqualTo("client_mismatch");
    }

    @Test
    void forgedTokenIsRejected() {
        var result = service.verify("forged.token", "0", "1.2.3.4").block(Duration.ofSeconds(5));

        assertThat(result).isInstanceOf(ChallengeService.VerifyResult.Failure.class);
        assertThat(((ChallengeService.VerifyResult.Failure) result).reason()).isEqualTo("bad_signature");
    }

    @Test
    void jsLiteVerifiesWithoutPow() {
        var challenge = service.issue("1.2.3.4", ChallengeService.ChallengeType.JS_LITE, 0);

        var result = service.verify(challenge.token(), "", "1.2.3.4").block(Duration.ofSeconds(5));

        assertThat(result).isInstanceOf(ChallengeService.VerifyResult.Success.class);
    }

    @Nested
    class PureLogic {

        @Test
        void difficultyScalesWithScoreAndIsCapped() {
            assertThat(service.adaptiveDifficulty(75, 0)).isEqualTo(16);
            assertThat(service.adaptiveDifficulty(85, 0)).isEqualTo(18);
            assertThat(service.adaptiveDifficulty(100, 0)).isEqualTo(21);
            assertThat(service.adaptiveDifficulty(100, 1)).isEqualTo(22);
            assertThat(service.adaptiveDifficulty(100, 10)).as("capped").isEqualTo(22);
        }

        @Test
        void datacenterClientsStartOneLevelHigher() {
            assertThat(service.adaptiveDifficulty(75, 1))
                    .isEqualTo(service.adaptiveDifficulty(75, 0) + 1);
        }

        @Test
        void leadingZeroBitsCountsCorrectly() {
            assertThat(ChallengeService.leadingZeroBits(new byte[]{0, 0, 0})).isEqualTo(24);
            assertThat(ChallengeService.leadingZeroBits(new byte[]{(byte) 0x80})).isZero();
            assertThat(ChallengeService.leadingZeroBits(new byte[]{0x0F})).isEqualTo(4);
            assertThat(ChallengeService.leadingZeroBits(new byte[]{0, 0x01})).isEqualTo(15);
        }

        @Test
        void scoreBelowJsLiteCutoffGetsJsLite() {
            assertThat(service.issue("c", 80).type()).isEqualTo(ChallengeService.ChallengeType.JS_LITE);
            assertThat(service.issue("c", 88).type()).isEqualTo(ChallengeService.ChallengeType.POW);
        }

        @Test
        void expiredPassIsInvalid() {
            // craft an already-expired pass with the same signer
            HmacSigner signer = new HmacSigner("test-secret");
            String expired = signer.sign("1.2.3.4|" + (java.time.Instant.now().getEpochSecond() - 10));
            assertThat(service.isValidPass(expired, "1.2.3.4")).isFalse();
        }
    }
}
