package com.relayhub.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record ApiKeyRequest(
        @NotBlank(message = "name cannot be blank")
        @Schema(description = "Descriptive name for the API key", example = "Production Gateway Ingestion Key")
        @jakarta.validation.constraints.Size(max = 128)
        String name,

        @Schema(description = "Role assigned to this key (ROLE_INGEST, ROLE_ADMIN)", example = "ROLE_INGEST", defaultValue = "ROLE_INGEST")
        String role,

        @Schema(description = "Optional validity period in days (e.g. 30, 90, 365, null for perpetual)", example = "90")
        Integer validityDays
) {}

