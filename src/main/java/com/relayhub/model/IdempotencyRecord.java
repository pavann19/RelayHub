package com.relayhub.model;

import java.time.Instant;

public record IdempotencyRecord(
        String tenantId,
        String idempotencyKey,
        String requestHash,
        IdempotencyStatus status,
        Integer responseStatus,
        String responseBody,
        Instant createdAt,
        Instant expiresAt
) {
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}

