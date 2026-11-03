package com.relayhub.signing;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class WebhookSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    public String generateSignatureHeader(String payload, String secret, long timestampSeconds) {
        String signature = computeHmacSha256(timestampSeconds + "." + payload, secret);
        return "t=" + timestampSeconds + ",v1=" + signature;
    }

    public String generateSignatureHeader(String payload, String secret) {
        return generateSignatureHeader(payload, secret, Instant.now().getEpochSecond());
    }

    public static String computeHmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(secretKey);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(rawHmac);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 signature", e);
        }
    }
}

