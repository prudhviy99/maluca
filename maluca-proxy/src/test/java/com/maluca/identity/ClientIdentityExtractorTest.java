package com.maluca.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import com.maluca.config.MalucaProperties;
import com.maluca.model.ClientIdentity;

class ClientIdentityExtractorTest {

    private static MalucaProperties props(boolean trustXff, List<String> trustedProxies) {
        return new MalucaProperties(
                new MalucaProperties.Upstream("http://localhost:8081", 5000, 30000, 100),
                new MalucaProperties.Identity(trustXff, trustedProxies),
                new MalucaProperties.Limits(true, 30, 10, 300, 5),
                List.of());
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
        ClientIdentityExtractor extractor = new ClientIdentityExtractor(props(false, List.of()));
        ClientIdentity identity = extractor.extract(exchange("203.0.113.7", "6.6.6.6"));

        assertThat(identity.ip()).isEqualTo("203.0.113.7");
        assertThat(identity.compositeKey()).isEqualTo("203.0.113.7");
    }

    @Test
    void ignoresXffWhenPeerIsNotTrusted() {
        // trust-xff is on, but the direct peer is not a trusted proxy — a spoofer
        ClientIdentityExtractor extractor = new ClientIdentityExtractor(props(true, List.of("10.0.0.1")));
        ClientIdentity identity = extractor.extract(exchange("203.0.113.7", "6.6.6.6"));

        assertThat(identity.ip()).isEqualTo("203.0.113.7");
    }

    @Test
    void honorsXffFromTrustedProxy() {
        ClientIdentityExtractor extractor = new ClientIdentityExtractor(props(true, List.of("10.0.0.1")));
        ClientIdentity identity = extractor.extract(exchange("10.0.0.1", "198.51.100.9"));

        assertThat(identity.ip()).isEqualTo("198.51.100.9");
    }

    @Test
    void takesRightmostUntrustedHopFromXffChain() {
        // client-spoofed left values must be ignored; the right-most untrusted
        // hop is the only address actually verified by a trusted proxy
        ClientIdentityExtractor extractor =
                new ClientIdentityExtractor(props(true, List.of("10.0.0.1", "10.0.0.2")));
        ClientIdentity identity =
                extractor.extract(exchange("10.0.0.1", "6.6.6.6, 198.51.100.9, 10.0.0.2"));

        assertThat(identity.ip()).isEqualTo("198.51.100.9");
    }

    @Test
    void fallsBackToPeerWhenXffMissing() {
        ClientIdentityExtractor extractor = new ClientIdentityExtractor(props(true, List.of("10.0.0.1")));
        ClientIdentity identity = extractor.extract(exchange("10.0.0.1", null));

        assertThat(identity.ip()).isEqualTo("10.0.0.1");
    }
}
