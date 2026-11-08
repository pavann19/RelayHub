package com.relayhub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record IngestEventRequest(
        @NotBlank(message = "eventType cannot be blank")
        @Schema(description = "Event type / topic name", example = "payment.completed")
        @jakarta.validation.constraints.Size(max = 128)
        @jakarta.validation.constraints.Pattern(regexp = "[^\\p{Cntrl}]+")
        String eventType,

        @NotNull(message = "payload cannot be null")
        @Schema(description = "Event payload JSON object or text", example = "{\"orderId\":\"ord_123\",\"amount\":99.99,\"currency\":\"EUR\"}")
        Object payload,

        @Schema(description = "Optional idempotency key (or pass via Idempotency-Key header)", example = "idem_9a8b7c6d5e")
        @jakarta.validation.constraints.Size(max = 128)
        String idempotencyKey
) {}

