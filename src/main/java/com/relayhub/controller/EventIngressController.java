package com.relayhub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relayhub.dto.IngestEventRequest;
import com.relayhub.dto.IngestEventResponse;
import com.relayhub.idempotency.IdempotencyOutcome;
import com.relayhub.idempotency.IdempotencyService;
import com.relayhub.kafka.EventBus;
import com.relayhub.metrics.RelayHubMetrics;
import com.relayhub.model.Event;
import com.relayhub.model.Subscription;
import com.relayhub.model.Tenant;
import com.relayhub.repository.EventRepository;
import com.relayhub.repository.SubscriptionRepository;
import com.relayhub.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Event Ingestion Gateway", description = "Ingests events, enforces rate limits, validates idempotency, and distributes to webhook subscribers.")
public class EventIngressController {

    private final EventRepository eventRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final IdempotencyService idempotencyService;
    private final EventBus eventBus;
    private final RelayHubMetrics metrics;
    private final ObjectMapper objectMapper;

    public EventIngressController(
            EventRepository eventRepository,
            SubscriptionRepository subscriptionRepository,
            IdempotencyService idempotencyService,
            EventBus eventBus,
            RelayHubMetrics metrics,
            ObjectMapper objectMapper
    ) {
        this.eventRepository = eventRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.idempotencyService = idempotencyService;
        this.eventBus = eventBus;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Ingest a new event",
            description = "Receives an event payload, verifies idempotency key, persists the event, and publishes it for reliable webhook delivery.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Event ingested successfully"),
                    @ApiResponse(responseCode = "200", description = "Idempotent replay of previously processed event"),
                    @ApiResponse(responseCode = "409", description = "Concurrent request with identical idempotency key is in progress"),
                    @ApiResponse(responseCode = "422", description = "Idempotency key reuse detected with mismatched payload"),
                    @ApiResponse(responseCode = "429", description = "Rate limit or monthly quota exceeded")
            }
    )
    public ResponseEntity<?> ingestEvent(
            @Valid @RequestBody IngestEventRequest request,
            @Parameter(description = "Optional Idempotency-Key header for duplicate protection")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader
    ) throws Exception {
        Tenant tenant = TenantContext.getTenant();
        String tenantId = tenant != null ? tenant.id() : "tenant_default";

        String effectiveIdempotencyKey = idempotencyKeyHeader != null && !idempotencyKeyHeader.isBlank()
                ? idempotencyKeyHeader.trim()
                : (request.idempotencyKey() != null ? request.idempotencyKey().trim() : null);

        String payloadJson = request.payload() instanceof String str
                ? str
                : objectMapper.writeValueAsString(request.payload());

        // 1. Idempotency Check & Lock Acquisition
        IdempotencyOutcome outcome = idempotencyService.checkOrAcquire(tenantId, effectiveIdempotencyKey, payloadJson);

        if (outcome.type() == IdempotencyOutcome.Type.CACHED) {
            HttpHeaders headers = new HttpHeaders();
            headers.add("Idempotent-Replay", "true");
            return ResponseEntity.status(outcome.cachedStatus())
                    .headers(headers)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(outcome.cachedBody());
        }

        if (outcome.type() == IdempotencyOutcome.Type.CONFLICT_IN_PROGRESS) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, outcome.message());
            problem.setTitle("Idempotency Conflict");
            problem.setInstance(URI.create("/api/v1/events"));
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }

        if (outcome.type() == IdempotencyOutcome.Type.MISMATCHED_PAYLOAD) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, outcome.message());
            problem.setTitle("Idempotency Key Mismatch");
            problem.setInstance(URI.create("/api/v1/events"));
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem);
        }

        // 2. Event Ingestion & Persistence
        String eventId = "evt_" + UUID.randomUUID();
        Event event = Event.create(eventId, tenantId, request.eventType(), effectiveIdempotencyKey, payloadJson);
        eventRepository.save(event);

        // 3. Count matching subscriptions for client feedback
        List<Subscription> matchingSubs = subscriptionRepository.findActiveByTenantId(tenantId).stream()
                .filter(s -> s.matchesEventType(request.eventType()))
                .toList();

        // 4. Publish Event to Event Bus (Kafka / Direct Dispatcher)
        eventBus.publish(event);

        IngestEventResponse responseDto = new IngestEventResponse(
                eventId,
                tenantId,
                request.eventType(),
                event.status(),
                matchingSubs.size(),
                event.createdAt()
        );

        String responseBodyJson = objectMapper.writeValueAsString(responseDto);

        // 5. Complete Idempotency record with cached response
        idempotencyService.complete(tenantId, effectiveIdempotencyKey, HttpStatus.CREATED.value(), responseBodyJson);

        metrics.recordGatewayRequest(tenantId, "POST", HttpStatus.CREATED.value());

        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(responseDto);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get event by ID", description = "Retrieves event metadata and current status")
    public ResponseEntity<?> getEvent(@PathVariable String id) {
        Tenant tenant = TenantContext.getTenant();
        String tenantId = tenant != null ? tenant.id() : "tenant_default";

        return eventRepository.findById(id)
                .filter(e -> e.tenantId().equals(tenantId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    @Operation(summary = "List events", description = "Lists recent events ingested by the authenticated tenant")
    public ResponseEntity<List<Event>> listEvents(@RequestParam(defaultValue = "50") int limit) {
        Tenant tenant = TenantContext.getTenant();
        String tenantId = tenant != null ? tenant.id() : "tenant_default";

        List<Event> events = eventRepository.findByTenantId(tenantId, Math.min(limit, 100));
        return ResponseEntity.ok(events);
    }
}

