package com.relayhub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record SubscriptionResponse(
        @Schema(description = "Subscription ID", example = "sub_01948b8e-d901-729c-99f5-7489a3cf1202")
        String id,

        @Schema(description = "Tenant ID", example = "tenant_default")
        String tenantId,

        @Schema(description = "Destination webhook URL", example = "https://api.merchant.com/webhooks/relayhub")
        String targetUrl,

        @Schema(description = "HMAC secret used to sign outbound webhooks", example = "sec_live_9f86d081884c7d659a2feaa0c55ad015")
        String secret,

        @Schema(description = "Subscribed event types", example = "*")
        String eventTypes,

        @Schema(description = "Whether the subscription is active", example = "true")
        boolean isActive,

        @Schema(description = "Configured maximum retries", example = "5")
        int maxRetries,

        @Schema(description = "HTTP request timeout in milliseconds", example = "5000")
        int timeoutMs,

        @Schema(description = "Timestamp when subscription was created", example = "2026-11-03T11:00:00Z")
        Instant createdAt
) {}

