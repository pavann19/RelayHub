package com.relayhub.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

public final class ApiKeyHasher {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyHasher() {}

    public static String generateRawKey(String environment) {
        byte[] randomBytes = new byte[24];
        RANDOM.nextBytes(randomBytes);
        String hex = HexFormat.of().formatHex(randomBytes);
        return "rlh_" + environment + "_" + hex;
    }

    public static String extractPrefix(String rawKey) {
        if (rawKey == null || rawKey.length() < 16) {
            return rawKey != null ? rawKey : "";
        }
        return rawKey.substring(0, 16);
    }

    public static String hashKey(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}

