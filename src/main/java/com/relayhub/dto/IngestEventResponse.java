package com.relayhub.dto;

import com.relayhub.model.EventStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record IngestEventResponse(
        @Schema(description = "Unique event identifier", example = "evt_01948b8e-d901-729c-99f5-7489a3cf1201")
        String eventId,

        @Schema(description = "Tenant identifier", example = "tenant_default")
        String tenantId,

        @Schema(description = "Event type", example = "payment.completed")
        String eventType,

        @Schema(description = "Current ingestion lifecycle status", example = "PENDING")
        EventStatus status,

        @Schema(description = "Number of active subscriptions matched for delivery", example = "2")
        int matchedSubscriptions,

        @Schema(description = "Timestamp when event was ingested", example = "2026-11-03T11:00:00Z")
        Instant createdAt
) {}

