package com.maluca.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import com.maluca.TestFixtures;
import com.maluca.model.ClientIdentity;

class ClientIdentityExtractorTest {

    private static ClientIdentityExtractor extractor(boolean trustXff, List<String> trustedProxies) {
        return new ClientIdentityExtractor(
                TestFixtures.properties(trustXff, trustedProxies), new FingerprintService());
    }

    private static MockServerWebExchange exchange(String peerIp, String xff) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/test")
                .remoteAddress(new InetSocketAddress(peerIp, 50000));
        if (xff != null) {
            builder = builder.header("X-Forwarded-For", xff);
        }
        return MockServerWebExchange.from(builder.build());
    }

    @Test
    void usesPeerIpByDefault() {
        ClientIdentity identity = extractor(false, List.of()).extract(exchange("203.0.113.7", "6.6.6.6"));

        assertThat(identity.ip()).isEqualTo("203.0.113.7");
        assertThat(identity.compositeKey()).isEqualTo("203.0.113.7");
    }

    @Test
    void ignoresXffWhenPeerIsNotTrusted() {
        // trust-xff is on, but the direct peer is not a trusted proxy — a spoofer
        ClientIdentity identity = extractor(true, List.of("10.0.0.1"))
                .extract(exchange("203.0.113.7", "6.6.6.6"));

        assertThat(identity.ip()).isEqualTo("203.0.113.7");
    }

    @Test
    void honorsXffFromTrustedProxy() {
        ClientIdentity identity = extractor(true, List.of("10.0.0.1"))
                .extract(exchange("10.0.0.1", "198.51.100.9"));

        assertThat(identity.ip()).isEqualTo("198.51.100.9");
    }

    @Test
    void takesRightmostUntrustedHopFromXffChain() {
        // client-spoofed left values must be ignored; the right-most untrusted
        // hop is the only address actually verified by a trusted proxy
        ClientIdentity identity = extractor(true, List.of("10.0.0.1", "10.0.0.2"))
                .extract(exchange("10.0.0.1", "6.6.6.6, 198.51.100.9, 10.0.0.2"));

        assertThat(identity.ip()).isEqualTo("198.51.100.9");
    }

    @Test
    void fallsBackToPeerWhenXffMissing() {
        ClientIdentity identity = extractor(true, List.of("10.0.0.1"))
                .extract(exchange("10.0.0.1", null));

        assertThat(identity.ip()).isEqualTo("10.0.0.1");
    }

    @Test
    void alwaysComputesFingerprintAndExposesAllLayers() {
        ClientIdentity identity = extractor(false, List.of())
                .extract(exchange("203.0.113.7", null));

        assertThat(identity.networkKey()).isEqualTo("203.0.113.7");
        assertThat(identity.fingerprintKey()).isNotBlank().hasSize(16);
        // NETWORK strategy: composite stays the IP even though layers exist
        assertThat(identity.compositeKey()).isEqualTo("203.0.113.7");
    }

    @Test
    void sessionCookieProducesSessionKey() {
        MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.get("/x")
                        .remoteAddress(new InetSocketAddress("203.0.113.7", 50000))
                        .cookie(new org.springframework.http.HttpCookie("session", "abc-123"))
                        .build());

        ClientIdentity identity = extractor(false, List.of()).extract(ex);

        assertThat(identity.sessionKey()).isNotBlank().hasSize(16);
    }
}
