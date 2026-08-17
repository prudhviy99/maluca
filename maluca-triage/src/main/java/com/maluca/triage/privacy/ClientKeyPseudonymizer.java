package com.maluca.triage.privacy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.maluca.triage.config.TriageProperties;

/** Converts correlatable client identifiers into stable, non-reversible IDs. */
@Component
public class ClientKeyPseudonymizer {

    private final boolean enabled;
    private final byte[] key;

    public ClientKeyPseudonymizer(TriageProperties properties) {
        this.enabled = properties.privacy().pseudonymizeClientKeys();
        this.key = properties.privacy().hmacKey().getBytes(StandardCharsets.UTF_8);
    }

    public String pseudonymize(String clientKey) {
        if (!enabled) {
            return clientKey;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            // Twenty bytes preserve ample collision resistance while keeping reports readable.
            return "client_" + HexFormat.of().formatHex(mac.doFinal(clientKey.getBytes(StandardCharsets.UTF_8)), 0, 20);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }
}
