package com.maluca.identity;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.springframework.http.HttpCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.maluca.config.MalucaProperties;
import com.maluca.model.ClientIdentity;
import com.maluca.model.RequestMeta;

/**
 * Builds the layered client identity: network (peer IP or trusted XFF),
 * session (hash of the configured session cookie), and passive fingerprint.
 *
 * <p>{@code X-Forwarded-For} is only honored when explicitly enabled AND the
 * direct peer is in the trusted-proxy list — otherwise any client could spoof
 * its identity with a single header. When trusted, we take the right-most
 * value not belonging to a trusted proxy (values to its left are
 * client-controlled and unverifiable).
 */
@Component
public class ClientIdentityExtractor {

    private final MalucaProperties.Identity cfg;
    private final FingerprintService fingerprintService;

    public ClientIdentityExtractor(MalucaProperties properties, FingerprintService fingerprintService) {
        this.cfg = properties.identity();
        this.fingerprintService = fingerprintService;
    }

    public ClientIdentity extract(ServerWebExchange exchange) {
        return extract(exchange, RequestMeta.from(exchange.getRequest()));
    }

    public ClientIdentity extract(ServerWebExchange exchange, RequestMeta meta) {
        return extract(exchange, meta, null);
    }

    /** {@code strategyOverride} lets a policy choose its own keying (null = global default). */
    public ClientIdentity extract(ServerWebExchange exchange, RequestMeta meta,
                                  MalucaProperties.Identity.KeyStrategy strategyOverride) {
        ClientIdentity network = ClientIdentity.ofIp(resolveClientIp(exchange));
        String sessionKey = sessionKey(exchange);
        String fingerprintKey = fingerprintService.fingerprint(meta);
        return network.withKeys(sessionKey, fingerprintKey,
                strategyOverride != null ? strategyOverride : cfg.strategy());
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        String peerIp = peerIp(exchange);

        if (cfg.trustXForwardedFor() && isTrustedProxy(peerIp)) {
            String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] hops = xff.split(",");
                for (int i = hops.length - 1; i >= 0; i--) {
                    String hop = hops[i].trim();
                    if (!hop.isEmpty() && !isTrustedProxy(hop)) {
                        return hop;
                    }
                }
            }
        }
        return peerIp;
    }

    private String sessionKey(ServerWebExchange exchange) {
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(cfg.sessionCookie());
        if (cookie == null || cookie.getValue().isBlank()) {
            return null;
        }
        return sha256Hex(cookie.getValue()).substring(0, 16);
    }

    private String peerIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        return remote.getAddress().getHostAddress();
    }

    private boolean isTrustedProxy(String ip) {
        List<String> trusted = cfg.trustedProxies();
        return trusted != null && trusted.contains(ip);
    }

    private static String sha256Hex(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
