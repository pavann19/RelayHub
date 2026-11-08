package com.relayhub.controller;

import com.relayhub.dto.ApiKeyRequest;
import com.relayhub.dto.ApiKeyResponse;
import com.relayhub.model.ApiKey;
import com.relayhub.model.Tenant;
import com.relayhub.repository.ApiKeyRepository;
import com.relayhub.security.ApiKeyHasher;
import com.relayhub.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/api-keys")
@Tag(name = "API Key Management", description = "Generate and revoke scoped API keys with prefix matching and SHA-256 storage.")
public class ApiKeyController {

    private final ApiKeyRepository repository;

    public ApiKeyController(ApiKeyRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    @Operation(summary = "Generate a new tenant API key")
    public ResponseEntity<ApiKeyResponse> createApiKey(@Valid @RequestBody ApiKeyRequest request) {
        Tenant tenant = TenantContext.getTenant();
        String tenantId = tenant != null ? tenant.id() : "tenant_default";

        String id = "key_" + UUID.randomUUID();
        String rawKey = ApiKeyHasher.generateRawKey("live");
        String prefix = ApiKeyHasher.extractPrefix(rawKey);
        String hash = ApiKeyHasher.hashKey(rawKey);

        String role = request.role() != null && !request.role().isBlank() ? request.role().trim() : "ROLE_INGEST";
        if (!java.util.Set.of("ROLE_INGEST", "ROLE_ADMIN", "ROLE_OBSERVE").contains(role)) {
            throw new IllegalArgumentException("Unsupported API key role");
        }
        Instant now = Instant.now();
        Instant expiresAt = request.validityDays() != null && request.validityDays() > 0
                ? now.plus(request.validityDays(), ChronoUnit.DAYS)
                : null;

        ApiKey apiKey = new ApiKey(id, tenantId, prefix, hash, request.name().trim(), role, true, now, expiresAt);
        repository.save(apiKey);

        // Return the full raw key only once
        ApiKeyResponse response = new ApiKeyResponse(
                id,
                tenantId,
                prefix,
                rawKey,
                apiKey.name(),
                apiKey.role(),
                apiKey.createdAt(),
                apiKey.expiresAt()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @Operation(summary = "List active API keys for tenant (raw keys are never returned)")
    public ResponseEntity<List<ApiKeyResponse>> listApiKeys() {
        Tenant tenant = TenantContext.getTenant();
        String tenantId = tenant != null ? tenant.id() : "tenant_default";

        List<ApiKeyResponse> keys = repository.findByTenantId(tenantId).stream()
                .map(k -> new ApiKeyResponse(
                        k.id(),
                        k.tenantId(),
                        k.keyPrefix(),
                        null, // Never expose raw secret
                        k.name(),
                        k.role(),
                        k.createdAt(),
                        k.expiresAt()
                ))
                .toList();
        return ResponseEntity.ok(keys);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke an API key")
    public ResponseEntity<Void> revokeApiKey(@PathVariable String id) {
        Tenant tenant = TenantContext.getTenant();
        String tenantId = tenant != null ? tenant.id() : "tenant_default";

        boolean revoked = repository.revoke(id, tenantId);
        return revoked ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}

