package com.relayhub.model;

import java.time.Instant;

public record Event(
        String id,
        String tenantId,
        String eventType,
        String idempotencyKey,
        String payload,
        EventStatus status,
        Instant createdAt
) {
    public static Event create(String id, String tenantId, String eventType, String idempotencyKey, String payload) {
        return new Event(id, tenantId, eventType, idempotencyKey, payload, EventStatus.PENDING, Instant.now());
    }

    public Event withStatus(EventStatus newStatus) {
        return new Event(id, tenantId, eventType, idempotencyKey, payload, newStatus, createdAt);
    }
}

