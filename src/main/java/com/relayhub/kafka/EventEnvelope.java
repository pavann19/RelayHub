package com.relayhub.kafka;

import com.relayhub.model.Event;
import java.time.Instant;

public record EventEnvelope(
        String eventId,
        String tenantId,
        String eventType,
        String idempotencyKey,
        String payload,
        long timestamp
) {
    public static EventEnvelope fromEvent(Event event) {
        return new EventEnvelope(
                event.id(),
                event.tenantId(),
                event.eventType(),
                event.idempotencyKey(),
                event.payload(),
                event.createdAt() != null ? event.createdAt().getEpochSecond() : Instant.now().getEpochSecond()
        );
    }
}

