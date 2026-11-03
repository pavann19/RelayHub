package com.relayhub.controller;

import com.relayhub.dto.SubscriptionRequest;
import com.relayhub.dto.SubscriptionResponse;
import com.relayhub.model.Subscription;
import com.relayhub.model.Tenant;
import com.relayhub.repository.SubscriptionRepository;
import com.relayhub.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/subscriptions")
@Tag(name = "Webhook Subscriptions", description = "Create and manage downstream webhook targets, HMAC secrets, and retry policies.")
public class SubscriptionController {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final SubscriptionRepository repository;

    public SubscriptionController(SubscriptionRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    @Operation(summary = "Register a new webhook subscription")
    public ResponseEntity<SubscriptionResponse> createSubscription(@Valid @RequestBody SubscriptionRequest request) {
        Tenant tenant = TenantContext.getTenant();
        String tenantId = tenant != null ? tenant.id() : "tenant_default";

        String id = "sub_" + UUID.randomUUID();
        String secret = request.secret() != null && !request.secret().isBlank()
                ? request.secret().trim()
                : generateSecret();

        String eventTypes = request.eventTypes() != null && !request.eventTypes().isBlank()
                ? request.eventTypes().trim()
                : "*";

        int maxRetries = request.maxRetries() != null ? Math.min(10, Math.max(1, request.maxRetries())) : 5;
        int timeoutMs = request.timeoutMs() != null ? Math.min(30000, Math.max(500, request.timeoutMs())) : 5000;

        Subscription sub = new Subscription(
                id,
                tenantId,
                request.targetUrl().trim(),
                secret,
                eventTypes,
                true,
                maxRetries,
                1000L,
                60000L,
                2.0,
                timeoutMs,
                Instant.now()
        );

        repository.save(sub);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(sub));
    }

    @GetMapping
    @Operation(summary = "List all subscriptions for the authenticated tenant")
    public ResponseEntity<List<SubscriptionResponse>> listSubscriptions() {
        Tenant tenant = TenantContext.getTenant();
        String tenantId = tenant != null ? tenant.id() : "tenant_default";

        List<SubscriptionResponse> list = repository.findByTenantId(tenantId).stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get subscription by ID")
    public ResponseEntity<SubscriptionResponse> getSubscription(@PathVariable String id) {
        Tenant tenant = TenantContext.getTenant();
        String tenantId = tenant != null ? tenant.id() : "tenant_default";

        return repository.findById(id)
                .filter(s -> s.tenantId().equals(tenantId))
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete subscription by ID")
    public ResponseEntity<Void> deleteSubscription(@PathVariable String id) {
        Tenant tenant = TenantContext.getTenant();
        String tenantId = tenant != null ? tenant.id() : "tenant_default";

        boolean deleted = repository.delete(id, tenantId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private SubscriptionResponse toResponse(Subscription s) {
        return new SubscriptionResponse(
                s.id(),
                s.tenantId(),
                s.targetUrl(),
                s.secret(),
                s.eventTypes(),
                s.isActive(),
                s.maxRetries(),
                s.timeoutMs(),
                s.createdAt()
        );
    }

    private String generateSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return "sec_live_" + HexFormat.of().formatHex(bytes);
    }
}

