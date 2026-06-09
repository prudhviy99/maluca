package com.maluca.identity;

import java.net.InetSocketAddress;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.maluca.config.MalucaProperties;
import com.maluca.model.ClientIdentity;

/**
 * Extracts the network identity of a client.
 *
 * <p>{@code X-Forwarded-For} is only honored when explicitly enabled AND the
 * direct peer is in the trusted-proxy list — otherwise any client could spoof
 * its identity with a single header. When trusted, we take the right-most
 * value not belonging to a trusted proxy (values to its left are
 * client-controlled and unverifiable).
 */
@Component
public class ClientIdentityExtractor {

    private final boolean trustXff;
    private final List<String> trustedProxies;

    public ClientIdentityExtractor(MalucaProperties properties) {
        this.trustXff = properties.identity().trustXForwardedFor();
        this.trustedProxies = properties.identity().trustedProxies();
    }

    public ClientIdentity extract(ServerWebExchange exchange) {
        String peerIp = peerIp(exchange);

        if (trustXff && isTrustedProxy(peerIp)) {
            String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] hops = xff.split(",");
                for (int i = hops.length - 1; i >= 0; i--) {
                    String hop = hops[i].trim();
                    if (!hop.isEmpty() && !isTrustedProxy(hop)) {
                        return ClientIdentity.ofIp(hop);
                    }
                }
            }
        }
        return ClientIdentity.ofIp(peerIp);
    }

    private String peerIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        return remote.getAddress().getHostAddress();
    }

    private boolean isTrustedProxy(String ip) {
        return trustedProxies.contains(ip);
    }
}
