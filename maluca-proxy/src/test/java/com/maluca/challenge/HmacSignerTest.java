package com.maluca.challenge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HmacSignerTest {

    private final HmacSigner signer = new HmacSigner("test-secret");

    @Test
    void roundTripsPayload() {
        String token = signer.sign("client|12345|16|abcd|POW");
        assertThat(signer.verify(token)).isEqualTo("client|12345|16|abcd|POW");
    }

    @Test
    void rejectsTamperedPayload() {
        String token = signer.sign("score=10");
        String forged = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("score=99".getBytes()) + token.substring(token.indexOf('.'));
        assertThat(signer.verify(forged)).isNull();
    }

    @Test
    void rejectsTamperedSignature() {
        String token = signer.sign("payload");
        String forged = token.substring(0, token.length() - 2) + "xx";
        assertThat(signer.verify(forged)).isNull();
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        String token = new HmacSigner("other-secret").sign("payload");
        assertThat(signer.verify(token)).isNull();
    }

    @Test
    void rejectsGarbage() {
        assertThat(signer.verify(null)).isNull();
        assertThat(signer.verify("")).isNull();
        assertThat(signer.verify("no-dot")).isNull();
        assertThat(signer.verify(".")).isNull();
        assertThat(signer.verify("not!base64.alsonot!")).isNull();
    }
}
