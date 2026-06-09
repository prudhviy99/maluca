package com.maluca.model;

import com.maluca.config.MalucaProperties.Identity.KeyStrategy;

/**
 * Layered client identity. The network key is always present (client IP or
 * trusted XFF). Session and fingerprint keys are optional refinements; the
 * composite key — what rate-limit and scoring state is keyed on — is chosen
 * by strategy. No single layer is decisive: rotating IPs keeps the
 * fingerprint, rotating fingerprints keeps the IP.
 */
public record ClientIdentity(
        String ip,
        String networkKey,
        String sessionKey,
        String fingerprintKey,
        String compositeKey) {

    public static ClientIdentity ofIp(String ip) {
        return new ClientIdentity(ip, ip, null, null, ip);
    }

    public ClientIdentity withKeys(String sessionKey, String fingerprintKey, KeyStrategy strategy) {
        String composite = switch (strategy) {
            case NETWORK -> networkKey;
            case FINGERPRINT -> fingerprintKey != null ? fingerprintKey : networkKey;
            case COMPOSITE -> networkKey
                    + "|" + (sessionKey != null ? sessionKey : "-")
                    + "|" + (fingerprintKey != null ? fingerprintKey : "-");
        };
        return new ClientIdentity(ip, networkKey, sessionKey, fingerprintKey, composite);
    }
}
