package com.relayhub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SubscriptionRequest(
        @NotBlank(message = "targetUrl cannot be blank")
        @Pattern(regexp = "^https?://.+", message = "targetUrl must be a valid HTTP or HTTPS URL")
        @Schema(description = "Destination webhook URL", example = "https://api.merchant.com/webhooks/relayhub")
        @jakarta.validation.constraints.Size(max = 1024)
        String targetUrl,

        @Schema(description = "Optional custom HMAC secret (min 16 chars). If omitted, RelayHub securely generates one.", example = "sec_live_9f86d081884c7d659a2feaa0c55ad015")
        @jakarta.validation.constraints.Size(min = 16, max = 255)
        String secret,

        @Schema(description = "Comma-separated list of event types or '*' for all events", example = "payment.completed,order.created")
        @jakarta.validation.constraints.Size(max = 512)
        String eventTypes,

        @Schema(description = "Maximum delivery retry attempts (1 to 10)", example = "5", defaultValue = "5")
        Integer maxRetries,

        @Schema(description = "Per-attempt HTTP timeout in milliseconds (500 to 30000)", example = "5000", defaultValue = "5000")
        Integer timeoutMs
) {}

