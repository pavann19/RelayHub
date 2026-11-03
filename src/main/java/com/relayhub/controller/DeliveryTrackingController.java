package com.relayhub.controller;

import com.relayhub.dto.DeliveryDto;
import com.relayhub.model.Tenant;
import com.relayhub.model.WebhookDelivery;
import com.relayhub.repository.DeliveryRepository;
import com.relayhub.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/deliveries")
@Tag(name = "Delivery Tracking & DLQ", description = "Audit log of webhook delivery attempts, dead-letter queue, and failure inspection.")
public class DeliveryTrackingController {

    private final DeliveryRepository repository;

    public DeliveryTrackingController(DeliveryRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Operation(summary = "List recent delivery attempts for tenant or by event ID")
    public ResponseEntity<List<DeliveryDto>> listDeliveries(
            @RequestParam(required = false) String eventId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        Tenant tenant = TenantContext.getTenant();
        String tenantId = tenant != null ? tenant.id() : "tenant_default";

        List<WebhookDelivery> deliveries;
        if (eventId != null && !eventId.isBlank()) {
            deliveries = repository.findByEventId(eventId.trim());
        } else {
            deliveries = repository.findByTenantId(tenantId, Math.min(limit, 100));
        }

        List<DeliveryDto> dtoList = deliveries.stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(dtoList);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get specific delivery attempt details")
    public ResponseEntity<DeliveryDto> getDelivery(@PathVariable String id) {
        Tenant tenant = TenantContext.getTenant();
        String tenantId = tenant != null ? tenant.id() : "tenant_default";

        return repository.findById(id)
                .filter(d -> d.tenantId().equals(tenantId))
                .map(this::toDto)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/dead-letters")
    @Operation(summary = "List all dead-lettered delivery attempts for the tenant")
    public ResponseEntity<List<DeliveryDto>> listDeadLetters() {
        Tenant tenant = TenantContext.getTenant();
        String tenantId = tenant != null ? tenant.id() : "tenant_default";

        List<DeliveryDto> deadLetters = repository.findDeadLettersByTenantId(tenantId).stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(deadLetters);
    }

    private DeliveryDto toDto(WebhookDelivery d) {
        return new DeliveryDto(
                d.id(),
                d.eventId(),
                d.subscriptionId(),
                d.tenantId(),
                d.attemptNumber(),
                d.status(),
                d.httpStatus(),
                d.requestHeaders(),
                d.responseHeaders(),
                d.responseBody(),
                d.errorMessage(),
                d.latencyMs(),
                d.nextRetryAt(),
                d.createdAt(),
                d.completedAt()
        );
    }
}

