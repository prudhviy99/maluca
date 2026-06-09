package com.maluca.policy;

import java.util.Map;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import com.maluca.config.MalucaProperties;

/**
 * Maps an API key to a client tier (free / pro / enterprise) from a static
 * config map. Production version: the same interface backed by an auth
 * service lookup with a short local cache; nothing downstream changes.
 */
@Component
public class ClientTierService {

    public static final String ANONYMOUS = "anonymous";
    private static final String API_KEY_HEADER = "X-Api-Key";

    private final Map<String, String> keyToTier;

    public ClientTierService(MalucaProperties properties) {
        this.keyToTier = properties.tierKeys();
    }

    public String tierOf(ServerHttpRequest request) {
        String apiKey = request.getHeaders().getFirst(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            return ANONYMOUS;
        }
        return keyToTier.getOrDefault(apiKey, ANONYMOUS);
    }
}
