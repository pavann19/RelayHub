package com.relayhub.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.relayhub.dto.IngestEventRequest;
import com.relayhub.idempotency.IdempotencyOutcome;
import com.relayhub.idempotency.IdempotencyService;
import com.relayhub.kafka.EventBus;
import com.relayhub.metrics.RelayHubMetrics;
import com.relayhub.model.Event;
import com.relayhub.model.IdempotencyRecord;
import com.relayhub.model.IdempotencyStatus;
import com.relayhub.model.Subscription;
import com.relayhub.model.Tenant;
import com.relayhub.model.TenantTier;
import com.relayhub.repository.EventRepository;
import com.relayhub.repository.IdempotencyRepository;
import com.relayhub.repository.SubscriptionRepository;
import com.relayhub.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EventIngressControllerTest {

    private MockMvc mockMvc;
    private FakeEventRepository eventRepository;
    private FakeSubscriptionRepository subscriptionRepository;
    private FakeIdempotencyRepository idempotencyRepository;
    private IdempotencyService idempotencyService;
    private FakeEventBus eventBus;
    private RelayHubMetrics metrics;
    private ObjectMapper objectMapper;

    private final Tenant testTenant = new Tenant("tenant_test", "Test Tenant", TenantTier.PRO, 100, 200, 100000L, null);

    @BeforeEach
    void setUp() {
        eventRepository = new FakeEventRepository();
        subscriptionRepository = new FakeSubscriptionRepository();
        idempotencyRepository = new FakeIdempotencyRepository();
        idempotencyService = new IdempotencyService(idempotencyRepository);
        eventBus = new FakeEventBus();
        metrics = new RelayHubMetrics(new SimpleMeterRegistry());
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        EventIngressController controller = new EventIngressController(
                eventRepository,
                subscriptionRepository,
                idempotencyService,
                eventBus,
                metrics,
                objectMapper
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        TenantContext.setTenant(testTenant);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Should successfully ingest valid event and return 201 Created")
    void shouldIngestValidEvent() throws Exception {
        IngestEventRequest request = new IngestEventRequest("order.completed", "{\"orderId\":\"ord_123\"}", null);

        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").exists())
                .andExpect(jsonPath("$.tenantId").value("tenant_test"))
                .andExpect(jsonPath("$.eventType").value("order.completed"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertEquals(1, eventRepository.savedEvents.size());
        assertEquals(1, eventBus.publishedEvents.size());
        assertEquals(request.payload(), eventRepository.savedEvents.getFirst().payload());
        assertEquals(request.payload(), eventBus.publishedEvents.getFirst().payload());
    }

    @Test
    @DisplayName("Should return 400 Bad Request when eventType is blank")
    void shouldRejectBlankEventType() throws Exception {
        IngestEventRequest request = new IngestEventRequest("", "{\"orderId\":\"ord_123\"}", null);

        mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.invalidParams.eventType").exists());
    }

    @Test
    @DisplayName("Should return 422 Unprocessable Entity when idempotency key reused with mismatched payload")
    void shouldReturn422OnIdempotencyMismatch() throws Exception {
        idempotencyRepository.records.put("tenant_test:key_mismatch", new IdempotencyRecord(
                "tenant_test",
                "key_mismatch",
                "different_hash_value_12345",
                IdempotencyStatus.COMPLETED,
                201,
                "{}",
                Instant.now(),
                Instant.now().plusSeconds(3600)
        ));

        IngestEventRequest request = new IngestEventRequest("order.completed", "{\"different\":true}", "key_mismatch");

        mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", "key_mismatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Idempotency Key Mismatch"));
    }

    @Test
    @DisplayName("Should return 409 Conflict when concurrent request with same idempotency key is in progress")
    void shouldReturn409OnIdempotencyConflict() throws Exception {
        String payload = "{\"orderId\":1}";
        String hash = com.relayhub.idempotency.RequestHasher.sha256(objectMapper.writeValueAsString(List.of("order.completed", payload)));

        idempotencyRepository.records.put("tenant_test:key_concurrent", new IdempotencyRecord(
                "tenant_test",
                "key_concurrent",
                hash,
                IdempotencyStatus.IN_PROGRESS,
                null,
                null,
                Instant.now(),
                Instant.now().plusSeconds(120)
        ));

        IngestEventRequest request = new IngestEventRequest("order.completed", payload, "key_concurrent");

        mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", "key_concurrent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Idempotency Conflict"));
    }

    @Test
    @DisplayName("Should return cached response with Idempotent-Replay header on duplicate committed request")
    void shouldReturnCachedResponseOnIdempotentReplay() throws Exception {
        String payload = "{\"orderId\":1}";
        String hash = com.relayhub.idempotency.RequestHasher.sha256(objectMapper.writeValueAsString(List.of("order.completed", payload)));
        String cachedJson = "{\"eventId\":\"evt_cached_123\",\"status\":\"DELIVERED\"}";

        idempotencyRepository.records.put("tenant_test:key_committed", new IdempotencyRecord(
                "tenant_test",
                "key_committed",
                hash,
                IdempotencyStatus.COMPLETED,
                200,
                cachedJson,
                Instant.now(),
                Instant.now().plusSeconds(3600)
        ));

        IngestEventRequest request = new IngestEventRequest("order.completed", payload, "key_committed");

        mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", "key_committed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.eventId").value("evt_cached_123"))
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    private static class FakeEventRepository extends EventRepository {
        final List<Event> savedEvents = new ArrayList<>();

        public FakeEventRepository() {
            super(null);
        }

        @Override
        public Event save(Event event) {
            savedEvents.add(event);
            return event;
        }
    }

    private static class FakeSubscriptionRepository extends SubscriptionRepository {
        public FakeSubscriptionRepository() {
            super(null);
        }

        @Override
        public List<Subscription> findActiveByTenantId(String tenantId) {
            return List.of();
        }
    }

    private static class FakeIdempotencyRepository extends IdempotencyRepository {
        final Map<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

        public FakeIdempotencyRepository() {
            super(null);
        }

        @Override
        public Optional<IdempotencyRecord> find(String tenantId, String key) {
            return Optional.ofNullable(records.get(tenantId + ":" + key));
        }

        @Override
        public boolean tryAcquire(String tenantId, String key, String hash, long ttl) {
            return records.putIfAbsent(tenantId + ":" + key, new IdempotencyRecord(
                    tenantId, key, hash, IdempotencyStatus.IN_PROGRESS, null, null, Instant.now(), Instant.now().plusSeconds(ttl)
            )) == null;
        }

        @Override
        public void complete(String tenantId, String key, int status, String body) {
            IdempotencyRecord cur = records.get(tenantId + ":" + key);
            if (cur != null) {
                records.put(tenantId + ":" + key, new IdempotencyRecord(
                        tenantId, key, cur.requestHash(), IdempotencyStatus.COMPLETED, status, body, cur.createdAt(), cur.expiresAt()
                ));
            }
        }
    }

    private static class FakeEventBus implements EventBus {
        final List<Event> publishedEvents = new ArrayList<>();

        @Override
        public void publish(Event event) {
            publishedEvents.add(event);
        }

        @Override
        public void publishDlq(Event event, String reason) {}
    }
}
