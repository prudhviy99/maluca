package com.maluca.challenge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 signing for stateless tokens (challenge tokens, pass cookies).
 * Token format: {@code base64url(payload) + "." + base64url(hmac(payload))}.
 *
 * The secret never leaves the server; clients can present tokens but not
 * mint or alter them. Verification is constant-time.
 */
public final class HmacSigner {

    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final SecretKeySpec key;

    public HmacSigner(String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String sign(String payload) {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        return B64E.encodeToString(payloadBytes) + "." + B64E.encodeToString(hmac(payloadBytes));
    }

    /** Returns the payload if the signature is valid, otherwise null. */
    public String verify(String token) {
        if (token == null) {
            return null;
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return null;
        }
        try {
            byte[] payloadBytes = B64D.decode(token.substring(0, dot));
            byte[] providedSig = B64D.decode(token.substring(dot + 1));
            byte[] expectedSig = hmac(payloadBytes);
            if (!MessageDigest.isEqual(expectedSig, providedSig)) {
                return null;
            }
            return new String(payloadBytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private byte[] hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(payload);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }
}
