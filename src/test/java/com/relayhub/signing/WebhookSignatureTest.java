package com.relayhub.signing;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSignatureTest {

    private final WebhookSigner signer = new WebhookSigner();
    private final String secret = "sec_live_9f86d081884c7d659a2feaa0c55ad015";
    private final String payload = "{\"orderId\":\"ord_123\",\"amount\":99.99}";

    @Test
    @DisplayName("Should generate valid signature header and verify successfully")
    void shouldGenerateAndVerifySignature() {
        long now = Instant.now().getEpochSecond();
        String header = signer.generateSignatureHeader(payload, secret, now);

        assertNotNull(header);
        assertTrue(header.startsWith("t=" + now + ",v1="));

        boolean valid = WebhookSignatureVerifier.verify(header, payload, secret, 300L, now);
        assertTrue(valid, "Signature should be valid for identical payload and secret");
    }

    @Test
    @DisplayName("Should reject tampered payload")
    void shouldRejectTamperedPayload() {
        long now = Instant.now().getEpochSecond();
        String header = signer.generateSignatureHeader(payload, secret, now);

        String tamperedPayload = "{\"orderId\":\"ord_123\",\"amount\":9999.99}";
        boolean valid = WebhookSignatureVerifier.verify(header, tamperedPayload, secret, 300L, now);

        assertFalse(valid, "Tampered payload must fail verification");
    }

    @Test
    @DisplayName("Should reject incorrect secret")
    void shouldRejectIncorrectSecret() {
        long now = Instant.now().getEpochSecond();
        String header = signer.generateSignatureHeader(payload, secret, now);

        boolean valid = WebhookSignatureVerifier.verify(header, payload, "wrong_secret_1234567890", 300L, now);
        assertFalse(valid, "Wrong secret must fail verification");
    }

    @Test
    @DisplayName("Should reject expired timestamp beyond tolerance window (replay attack protection)")
    void shouldRejectExpiredTimestamp() {
        long pastTimestamp = Instant.now().minusSeconds(600).getEpochSecond(); // 10 minutes ago
        String header = signer.generateSignatureHeader(payload, secret, pastTimestamp);

        long currentTimestamp = Instant.now().getEpochSecond();
        // Tolerance is 300 seconds (5 mins)
        boolean valid = WebhookSignatureVerifier.verify(header, payload, secret, 300L, currentTimestamp);

        assertFalse(valid, "Signature older than tolerance window must be rejected to prevent replay attacks");
    }

    @Test
    @DisplayName("Should reject future timestamp beyond tolerance window")
    void shouldRejectFutureTimestamp() {
        long futureTimestamp = Instant.now().plusSeconds(600).getEpochSecond(); // 10 minutes in future
        String header = signer.generateSignatureHeader(payload, secret, futureTimestamp);

        long currentTimestamp = Instant.now().getEpochSecond();
        boolean valid = WebhookSignatureVerifier.verify(header, payload, secret, 300L, currentTimestamp);

        assertFalse(valid, "Future timestamp beyond tolerance window must be rejected");
    }

    @Test
    @DisplayName("Should safely handle null or malformed header")
    void shouldHandleMalformedHeader() {
        assertFalse(WebhookSignatureVerifier.verify(null, payload, secret));
        assertFalse(WebhookSignatureVerifier.verify("", payload, secret));
        assertFalse(WebhookSignatureVerifier.verify("invalid_format", payload, secret));
        assertFalse(WebhookSignatureVerifier.verify("t=not_a_number,v1=123", payload, secret));
    }
    @Test
    void rejectsOverflowingReplayWindowArithmetic() {
        long now = 1_700_000_000L;
        long timestamp = now - Long.MIN_VALUE;
        String header = signer.generateSignatureHeader(payload, secret, timestamp);
        assertFalse(WebhookSignatureVerifier.verify(header, payload, secret, 300L, now));
        assertFalse(WebhookSignatureVerifier.verify(
                signer.generateSignatureHeader(payload, secret, Long.MIN_VALUE), payload, secret, 300L, 0));
    }

    @Test
    void rejectsDuplicateTimestampAndSignatureFields() {
        long now = 1_700_000_000L;
        String header = signer.generateSignatureHeader(payload, secret, now);
        assertFalse(WebhookSignatureVerifier.verify("t=1," + header, payload, secret, 300L, now));
        assertFalse(WebhookSignatureVerifier.verify("v1=invalid," + header, payload, secret, 300L, now));
    }

    @Test
    void acceptsExactWindowBoundariesAndRejectsOutsideThem() {
        long now = 1_700_000_000L;
        for (long offset : new long[]{-300, 0, 300}) {
            assertTrue(WebhookSignatureVerifier.verify(signer.generateSignatureHeader(payload, secret, now + offset),
                    payload, secret, 300L, now));
        }
        for (long offset : new long[]{-301, 301}) {
            assertFalse(WebhookSignatureVerifier.verify(signer.generateSignatureHeader(payload, secret, now + offset),
                    payload, secret, 300L, now));
        }
        assertFalse(WebhookSignatureVerifier.verify(signer.generateSignatureHeader(payload, secret, now),
                payload, secret, -1L, now));
    }

}

