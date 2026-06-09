package com.maluca.model;

/**
 * Layered client identity. The network key is always present (client IP or
 * trusted XFF). Session and fingerprint keys are optional refinements added
 * by later identity strategies; the composite key is what rate-limit and
 * scoring state is keyed on.
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
}
