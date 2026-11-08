package com.relayhub.integration;

import com.relayhub.model.Tenant;
import com.relayhub.model.TenantTier;
import com.relayhub.repository.TenantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
public class TestcontainersIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("relayhub_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("relayhub.kafka.enabled", () -> "true");
    }

    @Autowired(required = false)
    private TenantRepository tenantRepository;

    @Test
    @DisplayName("Should initialize PostgreSQL schema and verify persistence via Testcontainers")
    void shouldPersistAndRetrieveTenantInPostgres() {
        org.junit.jupiter.api.Assertions.assertNotNull(tenantRepository);

        Tenant testTenant = Tenant.of("tc_tenant_1", "Testcontainers Tenant", TenantTier.ENTERPRISE);
        tenantRepository.save(testTenant);

        Optional<Tenant> retrieved = tenantRepository.findById("tc_tenant_1");
        assertTrue(retrieved.isPresent(), "Tenant should be persisted and retrieved from PostgreSQL");
        assertEquals("Testcontainers Tenant", retrieved.get().name());
        assertEquals(TenantTier.ENTERPRISE, retrieved.get().tier());
    }
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired com.relayhub.repository.EventRepository events;
    @Autowired com.relayhub.repository.DeliveryRepository deliveries;
    @Autowired com.relayhub.repository.SubscriptionRepository subscriptions;
    @Autowired com.relayhub.dispatcher.WebhookDispatcher dispatcher;
    @Autowired com.relayhub.kafka.KafkaEventConsumer consumer;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("transportEventBus") com.relayhub.kafka.EventBus transport;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    private final java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
    private com.relayhub.dispatcher.DurableWorkScheduler worker() {
        return new com.relayhub.dispatcher.DurableWorkScheduler(jdbc, transactions, transport, events, dispatcher);
    }

    private java.net.http.HttpResponse<String> api(String path, String key, String body, String idem) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (key != null) request.header("X-API-Key", key);
        if (idem != null) request.header("Idempotency-Key", idem);
        if (body == null) request.GET(); else request.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
    }

    private static final String INGEST = "rlh_live_demo1234567890abcdef";
    private static final String ADMIN = "rlh_admin_9876543210fedcba";

    @Test
    void enforcesManagementRolesAndRejectsUnknownRoles() throws Exception {
        for (String path : new String[]{"/api/v1/api-keys", "/api/v1/subscriptions", "/api/v1/deliveries/replay"}) {
            assertEquals(403, api(path, INGEST, "{}", null).statusCode(), path);
        }
        assertEquals(400, api("/api/v1/api-keys", ADMIN, "{\"name\":\"bad-role\",\"role\":\"ROLE_SUPERUSER\"}", null).statusCode());
        assertEquals(403, api("/actuator/metrics", null, null, null).statusCode());
        assertEquals(200, api("/actuator/health", null, null, null).statusCode());
        assertEquals(400, api("/api/v1/deliveries/replay", ADMIN, "{}", null).statusCode());
        assertEquals(400, api("/api/v1/subscriptions", ADMIN,
                "{\"targetUrl\":\"http://169.254.169.254/latest/meta-data\"}", null).statusCode());
    }

    @Test
    void bindsIdempotencyToEventTypeAndPayload() throws Exception {
        String key = "idem_" + java.util.UUID.randomUUID();
        String body = "{\"eventType\":\"type.first\",\"payload\":{\"value\":1}}";
        var first = api("/api/v1/events", INGEST, body, key);
        assertEquals(201, first.statusCode(), first.body());
        assertEquals("{\"value\":1}", jdbc.queryForObject("SELECT payload FROM events WHERE idempotency_key = ?", String.class, key));
        var duplicate = api("/api/v1/events", INGEST, body, key);
        assertEquals(201, duplicate.statusCode());
        assertEquals("true", duplicate.headers().firstValue("Idempotent-Replay").orElseThrow());
        assertEquals(first.body(), duplicate.body());
        assertEquals(422, api("/api/v1/events", INGEST, body.replace("type.first", "type.second"), key).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM events WHERE idempotency_key = ?", Integer.class, key));
    }

    @Test
    void rollsBackEventAndIdempotencyWhenOutboxInsertFails() throws Exception {
        String key = "rollback_" + java.util.UUID.randomUUID();
        String body = "{\"eventType\":\"atomic.test\",\"payload\":{\"value\":1}}";
        jdbc.execute("ALTER TABLE event_outbox ADD CONSTRAINT inject_failure CHECK (kind <> 'EVENT') NOT VALID");
        try {
            assertEquals(500, api("/api/v1/events", INGEST, body, key).statusCode());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM events WHERE idempotency_key = ?", Integer.class, key));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE idempotency_key = ?", Integer.class, key));
        } finally {
            jdbc.execute("ALTER TABLE event_outbox DROP CONSTRAINT inject_failure");
        }
        assertEquals(201, api("/api/v1/events", INGEST, body, key).statusCode());
    }

    @Test
    void recoversExpiredIdempotencyAndPersistsCompletedRetention() {
        String key = "expired_" + java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO idempotency_records(tenant_id,idempotency_key,request_hash,status,expires_at) VALUES ('tenant_default', ?, 'old', 'IN_PROGRESS', CURRENT_TIMESTAMP - INTERVAL '1 minute')", key);
        var repository = new com.relayhub.repository.IdempotencyRepository(jdbc);
        assertTrue(repository.tryAcquire("tenant_default", key, "new", 120));
        repository.complete("tenant_default", key, 201, "{}");
        assertTrue(repository.find("tenant_default", key).orElseThrow().expiresAt().isAfter(java.time.Instant.now().plusSeconds(86000)));
    }

    @Test
    void sharedQuotaIsAtomicAcrossInstancesAndResetsAtUtcMonthBoundary() throws Exception {
        var tenant = Tenant.of("quota_" + java.util.UUID.randomUUID(), "Quota test", TenantTier.ENTERPRISE);
        tenant = new Tenant(tenant.id(), tenant.name(), tenant.tier(), 1000, 100, 5L, tenant.createdAt());
        tenantRepository.save(tenant);
        var one = new com.relayhub.ratelimit.JdbcTokenBucketRateLimiter(jdbc, transactions);
        var two = new com.relayhub.ratelimit.JdbcTokenBucketRateLimiter(jdbc, transactions);
        final Tenant scoped = tenant;
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(10)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 20; i++) {
                var limiter = i % 2 == 0 ? one : two;
                jobs.add(pool.submit(() -> limiter.tryConsume(scoped, 1).allowed()));
            }
            int allowed = 0;
            for (var job : jobs) if (job.get()) allowed++;
            assertEquals(5, allowed);
        }
        var restarted = new com.relayhub.ratelimit.JdbcTokenBucketRateLimiter(jdbc, transactions);
        org.junit.jupiter.api.Assertions.assertFalse(restarted.tryConsume(tenant, 1).allowed());
        jdbc.update("UPDATE tenant_rate_limits SET quota_month = '2000-01' WHERE tenant_id = ?", tenant.id());
        assertTrue(restarted.tryConsume(tenant, 1).allowed());
        assertEquals(1L, jdbc.queryForObject("SELECT usage FROM tenant_rate_limits WHERE tenant_id = ?", Long.class, tenant.id()));
    }

    @Test
    void kafkaOutboxRecoveryAndRedeliveryMaterializeOnlyOneDelivery() throws Exception {
        var event = com.relayhub.model.Event.create("evt_" + java.util.UUID.randomUUID(), "tenant_default", "kafka.test", null, "{}");
        var sub = new com.relayhub.model.Subscription("sub_" + java.util.UUID.randomUUID(), "tenant_default",
                "http://127.0.0.1:1/hook", "secret-test-secret", "kafka.test", true, 3, 10, 20, 2.0, 500, java.time.Instant.now());
        subscriptions.save(sub);
        events.save(event);
        new com.relayhub.kafka.OutboxEventBus(jdbc).publish(event);
        var brokenTransport = new com.relayhub.kafka.EventBus() {
            public void publish(com.relayhub.model.Event value) { throw new IllegalStateException("injected broker outage"); }
            public void publishDlq(com.relayhub.model.Event value, String reason) { throw new IllegalStateException("injected broker outage"); }
        };
        new com.relayhub.dispatcher.DurableWorkScheduler(jdbc, transactions, brokenTransport, events, dispatcher).publishPending();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM event_outbox WHERE event_id = ? AND published_at IS NULL", Integer.class, event.id()));
        jdbc.update("UPDATE event_outbox SET next_attempt_at = CURRENT_TIMESTAMP WHERE event_id = ?", event.id());
        worker().publishPending();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() ->
                assertEquals(1, deliveries.findByEventId(event.id()).size()));
        String envelope = mapper.writeValueAsString(com.relayhub.kafka.EventEnvelope.fromEvent(event));
        consumer.onEventReceived(envelope);
        consumer.onEventReceived(envelope);
        assertEquals(1, deliveries.findByEventId(event.id()).size());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM event_outbox WHERE event_id = ? AND published_at IS NULL", Integer.class, event.id()));
    }

    @Test
    void deliveryQueryDoesNotExposeAnotherTenantsAudit() throws Exception {
        String other = "other_" + java.util.UUID.randomUUID();
        tenantRepository.save(Tenant.of(other, "Other tenant", TenantTier.FREE));
        var event = com.relayhub.model.Event.create("evt_" + java.util.UUID.randomUUID(), other, "private", null, "{}");
        events.save(event);
        var sub = new com.relayhub.model.Subscription("sub_" + java.util.UUID.randomUUID(), other,
                "https://example.invalid/hook", "secret-test-secret", "*", true, 3, 10, 20, 2.0, 500, java.time.Instant.now());
        subscriptions.save(sub);
        deliveries.save(com.relayhub.model.WebhookDelivery.initial("del_" + java.util.UUID.randomUUID(), event.id(), sub.id(), other));
        var response = api("/api/v1/deliveries?eventId=" + event.id(), INGEST, null, null);
        assertEquals(200, response.statusCode());
        assertEquals("[]", response.body());
        String deliveryId = deliveries.findByEventId(event.id()).getFirst().id();
        assertEquals(404, api("/api/v1/deliveries/" + deliveryId + "/attempts", INGEST, null, null).statusCode());
    }

    @Test
    void tokenBucketStateAndRefillAreSharedAcrossInstances() {
        Tenant tenant = new Tenant("bucket_" + java.util.UUID.randomUUID(), "Shared bucket", TenantTier.FREE, 1, 2, 100L, java.time.Instant.now());
        tenantRepository.save(tenant);
        var one = new com.relayhub.ratelimit.JdbcTokenBucketRateLimiter(jdbc, transactions);
        var two = new com.relayhub.ratelimit.JdbcTokenBucketRateLimiter(jdbc, transactions);
        assertTrue(one.tryConsume(tenant, 1).allowed());
        assertTrue(two.tryConsume(tenant, 1).allowed());
        org.junit.jupiter.api.Assertions.assertFalse(one.tryConsume(tenant, 1).allowed());
        var restarted = new com.relayhub.ratelimit.JdbcTokenBucketRateLimiter(jdbc, transactions);
        org.junit.jupiter.api.Assertions.assertFalse(restarted.tryConsume(tenant, 1).allowed());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).until(() -> restarted.tryConsume(tenant, 1).allowed());
        assertEquals(3L, jdbc.queryForObject("SELECT usage FROM tenant_rate_limits WHERE tenant_id = ?", Long.class, tenant.id()));
    }

    @Test
    void disablingSubscriptionPreservesAuditAndCancelsPendingWork() {
        var event = com.relayhub.model.Event.create("evt_" + java.util.UUID.randomUUID(), "tenant_default", "disabled", null, "{}");
        events.save(event);
        var sub = new com.relayhub.model.Subscription("sub_" + java.util.UUID.randomUUID(), "tenant_default",
                "https://example.invalid/hook", "secret-test-secret", "disabled", true, 3, 10, 20, 2.0, 500, java.time.Instant.now());
        subscriptions.save(sub);
        String id = "del_" + java.util.UUID.randomUUID();
        deliveries.save(com.relayhub.model.WebhookDelivery.initial(id, event.id(), sub.id(), event.tenantId())
                .completeSuccess(200, "", "", "ok", 1));
        assertEquals(1, deliveries.findAttemptSnapshots(id).size());
        assertTrue(subscriptions.delete(sub.id(), event.tenantId()));
        assertEquals(1, deliveries.findAttemptSnapshots(id).size());
        String pendingId = "del_" + java.util.UUID.randomUUID();
        deliveries.save(com.relayhub.model.WebhookDelivery.initial(pendingId, event.id(), sub.id(), event.tenantId()));
        assertEquals(com.relayhub.model.DeliveryStatus.CANCELLED, dispatcher.retrySingleDelivery(pendingId).status());
        assertEquals(0, deliveries.findAttemptSnapshots(pendingId).size());
        assertEquals(1, deliveries.findAttemptSnapshots(id).size());
    }

    @Test
    void failingOutboxItemDoesNotBlockHealthyItem() {
        String bad = "evt_" + java.util.UUID.randomUUID(), good = "evt_" + java.util.UUID.randomUUID();
        for (String id : java.util.List.of(bad, good)) {
            var event = com.relayhub.model.Event.create(id, "tenant_default", "isolation", null, "{}");
            events.save(event); new com.relayhub.kafka.OutboxEventBus(jdbc).publish(event);
        }
        var published = new java.util.ArrayList<String>();
        var selective = new com.relayhub.kafka.EventBus() {
            public void publish(com.relayhub.model.Event event) {
                if (event.id().equals(bad)) throw new IllegalStateException("isolated poison item");
                published.add(event.id());
            }
            public void publishDlq(com.relayhub.model.Event event, String reason) {}
        };
        var scheduler = new com.relayhub.dispatcher.DurableWorkScheduler(jdbc, transactions, selective, events, dispatcher);
        scheduler.publishPending();
        assertTrue(published.contains(good));
        assertEquals(1, jdbc.queryForObject("SELECT failure_count FROM event_outbox WHERE event_id = ?", Integer.class, bad));
        for (int attempt = 0; attempt < 4; attempt++) {
            jdbc.update("UPDATE event_outbox SET next_attempt_at = CURRENT_TIMESTAMP WHERE event_id = ?", bad);
            scheduler.publishPending();
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM event_outbox WHERE event_id = ? AND quarantined_at IS NOT NULL", Integer.class, bad));
    }

    @Test
    void poisonKafkaRecordIsDurablyQuarantinedBeforeRecovery() {
        var handler = new com.relayhub.config.KafkaReliabilityConfig().kafkaErrorHandler(jdbc);
        var record = new org.apache.kafka.clients.consumer.ConsumerRecord<String,String>("poison-test", 0, 42L, "tenant", "malformed-json");
        var kafkaConsumer = org.mockito.Mockito.mock(org.apache.kafka.clients.consumer.Consumer.class);
        var container = org.mockito.Mockito.mock(org.springframework.kafka.listener.MessageListenerContainer.class);
        // Exercise the configured handler until its bounded backoff invokes the durable recoverer.
        boolean recovered = false;
        for (int i = 0; i < 4 && !recovered; i++) recovered = handler.handleOne(new IllegalStateException("bad record"), record, kafkaConsumer, container);
        assertTrue(recovered);
        assertEquals("malformed-json", jdbc.queryForObject("SELECT payload FROM kafka_quarantine WHERE topic='poison-test' AND record_offset=42", String.class));
    }

    @Test
    void inputLimitsAndEmptyReplayReturnAccurateResponses() throws Exception {
        assertEquals(400, api("/api/v1/events", INGEST,
                mapper.writeValueAsString(java.util.Map.of("eventType", "x".repeat(129), "payload", "ok")), null).statusCode());
        assertEquals(400, api("/api/v1/events", INGEST,
                "{\"eventType\":\"valid\",\"payload\":{}}", "k".repeat(129)).statusCode());
        assertEquals(413, api("/api/v1/events", INGEST,
                mapper.writeValueAsString(java.util.Map.of("eventType", "valid", "payload", "x".repeat(1048576))), null).statusCode());
        var event = com.relayhub.model.Event.create("evt_" + java.util.UUID.randomUUID(), "tenant_default", "empty", null, "{}");
        events.save(event);
        var response = api("/api/v1/deliveries/replay", ADMIN, mapper.writeValueAsString(java.util.Map.of("eventId", event.id())), null);
        assertEquals(200, response.statusCode());
        assertEquals(0, mapper.readTree(response.body()).get("replayedCount").asInt());
    }

}
