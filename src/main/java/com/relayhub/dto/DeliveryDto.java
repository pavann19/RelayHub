package com.relayhub.dto;

import com.relayhub.model.DeliveryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record DeliveryDto(
        @Schema(description = "Delivery Attempt ID", example = "del_01948b8e-d901-729c-99f5-7489a3cf1204")
        String id,

        @Schema(description = "Associated Event ID", example = "evt_01948b8e-d901-729c-99f5-7489a3cf1201")
        String eventId,

        @Schema(description = "Subscription ID", example = "sub_01948b8e-d901-729c-99f5-7489a3cf1202")
        String subscriptionId,

        @Schema(description = "Tenant ID", example = "tenant_default")
        String tenantId,

        @Schema(description = "Attempt number (1-based)", example = "1")
        int attemptNumber,

        @Schema(description = "Delivery attempt lifecycle status", example = "SUCCESS")
        DeliveryStatus status,

        @Schema(description = "Received HTTP response status code", example = "200")
        Integer httpStatus,

        @Schema(description = "Outbound HTTP request headers")
        String requestHeaders,

        @Schema(description = "Inbound HTTP response headers")
        String responseHeaders,

        @Schema(description = "Inbound HTTP response body preview")
        String responseBody,

        @Schema(description = "Error description if failed", example = "Connection refused to downstream host")
        String errorMessage,

        @Schema(description = "Roundtrip latency in milliseconds", example = "42")
        long latencyMs,

        @Schema(description = "Scheduled next retry timestamp (if RETRYING)")
        Instant nextRetryAt,

        @Schema(description = "Creation timestamp")
        Instant createdAt,

        @Schema(description = "Completion timestamp")
        Instant completedAt
) {}

