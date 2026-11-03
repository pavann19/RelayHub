package com.relayhub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record ApiKeyResponse(
        @Schema(description = "API key ID", example = "key_01948b8e-d901-729c-99f5-7489a3cf1203")
        String id,

        @Schema(description = "Tenant ID", example = "tenant_default")
        String tenantId,

        @Schema(description = "Display prefix of the key", example = "rlh_live_9a8b")
        String keyPrefix,

        @Schema(description = "Full raw API key. Shown ONLY ONCE upon creation. Store securely!", example = "rlh_live_9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d")
        String rawKey,

        @Schema(description = "Key name", example = "Production Gateway Ingestion Key")
        String name,

        @Schema(description = "Assigned security role", example = "ROLE_INGEST")
        String role,

        @Schema(description = "Creation timestamp")
        Instant createdAt,

        @Schema(description = "Expiration timestamp (null if perpetual)")
        Instant expiresAt
) {}

