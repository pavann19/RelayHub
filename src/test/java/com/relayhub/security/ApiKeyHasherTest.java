package com.relayhub.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyHasherTest {

    @Test
    @DisplayName("Should generate structured API key with environment prefix")
    void shouldGenerateStructuredKey() {
        String key = ApiKeyHasher.generateRawKey("live");
        assertNotNull(key);
        assertTrue(key.startsWith("rlh_live_"));
        assertTrue(key.length() > 30);
    }

    @Test
    @DisplayName("Should extract prefix correctly")
    void shouldExtractPrefix() {
        String key = "rlh_live_0123456789abcdef";
        String prefix = ApiKeyHasher.extractPrefix(key);
        assertEquals("rlh_live_0123456", prefix);
    }

    @Test
    @DisplayName("Should generate deterministic SHA-256 hash")
    void shouldGenerateDeterministicHash() {
        String key = "rlh_live_demo1234567890abcdef";
        String hash1 = ApiKeyHasher.hashKey(key);
        String hash2 = ApiKeyHasher.hashKey(key);

        assertEquals(hash1, hash2);
        assertEquals("cc907404f3d66fadd66fc43b1f2e7fa42c66ec671b598bd4efec622863aea79e", hash1);
    }

    @Test
    @DisplayName("Different keys must produce distinct hashes")
    void shouldProduceDistinctHashes() {
        String key1 = ApiKeyHasher.generateRawKey("live");
        String key2 = ApiKeyHasher.generateRawKey("live");

        assertNotEquals(key1, key2);
        assertNotEquals(ApiKeyHasher.hashKey(key1), ApiKeyHasher.hashKey(key2));
    }
}

