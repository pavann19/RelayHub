package com.relayhub.model;

import java.time.Instant;

public record ApiKey(
        String id,
        String tenantId,
        String keyPrefix,
        String keyHash,
        String name,
        String role,
        boolean isActive,
        Instant createdAt,
        Instant expiresAt
) {
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return isActive && !isExpired();
    }
}

