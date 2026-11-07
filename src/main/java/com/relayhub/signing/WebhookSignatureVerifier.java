package com.relayhub.signing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

public final class WebhookSignatureVerifier {

    private static final long DEFAULT_TOLERANCE_SECONDS = 300L; // 5 minutes

    private WebhookSignatureVerifier() {}

    public static boolean verify(String header, String payload, String secret) {
        return verify(header, payload, secret, DEFAULT_TOLERANCE_SECONDS, Instant.now().getEpochSecond());
    }

    public static boolean verify(String header, String payload, String secret, long toleranceSeconds, long currentTimestamp) {
        if (header == null || header.isBlank() || payload == null || secret == null || toleranceSeconds < 0) {
            return false;
        }

        Long timestamp = null;
        String signature = null;

        String[] parts = header.split(",");
        for (String part : parts) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                String key = kv[0].trim();
                String value = kv[1].trim();
                if ("t".equals(key)) {
                    if (timestamp != null) return false;
                    try {
                        timestamp = Long.parseLong(value);
                    } catch (NumberFormatException ignored) {
                        return false;
                    }
                } else if ("v1".equals(key)) {
                    if (signature != null) return false;
                    signature = value;
                }
            }
        }

        if (timestamp == null || signature == null) {
            return false;
        }

        // Timestamp tolerance check against replay attacks
        try {
            long distance = currentTimestamp >= timestamp
                    ? Math.subtractExact(currentTimestamp, timestamp)
                    : Math.subtractExact(timestamp, currentTimestamp);
            if (distance > toleranceSeconds) return false;
        } catch (ArithmeticException overflow) {
            return false;
        }

        String expectedSignature = WebhookSigner.computeHmacSha256(timestamp + "." + payload, secret);

        // Constant-time comparison to prevent timing side-channel attacks
        return MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8),
                expectedSignature.getBytes(StandardCharsets.UTF_8)
        );
    }
}

