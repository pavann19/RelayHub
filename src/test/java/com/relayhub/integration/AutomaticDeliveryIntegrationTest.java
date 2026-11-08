package com.relayhub.integration;

import com.relayhub.dispatcher.DurableWorkScheduler;
import com.relayhub.dispatcher.WebhookDispatcher;
import com.relayhub.kafka.EventBus;
import com.relayhub.model.*;
import com.relayhub.repository.*;
import com.relayhub.security.WebhookTargetPolicy;
import com.relayhub.dispatcher.HttpWebhookClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "relayhub.kafka.enabled=false", "relayhub.workers.enabled=true", "relayhub.workers.poll-ms=50",
        "relayhub.webhook.allowed-hosts=127.0.0.1", "relayhub.webhook.allow-private-targets=true"})
class AutomaticDeliveryIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    static HttpServer receiver;
    static final ConcurrentHashMap<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, Integer> successesAfter = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, String> deliveryIds = new ConcurrentHashMap<>();
    @BeforeAll static void startReceiver() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int count = calls.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            String deliveryId = exchange.getRequestHeaders().getFirst("X-RelayHub-Delivery");
            if (deliveryId != null) deliveryIds.put(path + ":" + count, deliveryId);
            exchange.getRequestBody().readAllBytes();
            int status = count <= successesAfter.getOrDefault(path, 0) ? 503 : 200;
            if (path.equals("/redirect")) { status = 302; exchange.getResponseHeaders().add("Location", url("/forbidden")); }
            byte[] body = new byte[path.equals("/large") ? 1024 * 1024 : 2];
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            catch (java.io.IOException ignored) { /* Bounded response subscribers deliberately cancel previews. */ }
            exchange.close();
        });
        receiver.start();
    }
    @AfterAll static void stopReceiver() { if (receiver != null) receiver.stop(0); }
    static String url(String path) { return "http://127.0.0.1:" + receiver.getAddress().getPort() + path; }

    @Autowired TenantRepository tenants;
    @Autowired EventRepository events;
    @Autowired SubscriptionRepository subscriptions;
    @Autowired DeliveryRepository deliveries;
    @Autowired EventBus bus;
    @Autowired WebhookDispatcher dispatcher;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

    private Event fixture(String type, String path, int maxAttempts) {
        String tenantId = "tenant_" + UUID.randomUUID();
        tenants.save(Tenant.of(tenantId, "Isolated delivery test", TenantTier.PRO));
        var event = Event.create("evt_" + UUID.randomUUID(), tenantId, type, null, "{}");
        subscriptions.save(new Subscription("sub_" + UUID.randomUUID(), tenantId, url(path), "secret-test-secret",
                type, true, maxAttempts, 50, 100, 2.0, 1000, Instant.now()));
        // Same transaction boundary as HTTP ingestion, against an isolated database.
        transactions.executeWithoutResult(tx -> { events.save(event); bus.publish(event); });
        return event;
    }

    @Test void automaticallyRetriesRealHttpAndPreservesEveryAttempt() {
        String path = "/flaky-" + UUID.randomUUID();
        successesAfter.put(path, 2);
        Event event = fixture("flaky", path, 5);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(EventStatus.DELIVERED, events.findById(event.id()).orElseThrow().status()));
        WebhookDelivery delivery = deliveries.findByEventId(event.id()).getFirst();
        assertEquals(3, delivery.attemptNumber());
        assertEquals(3, calls.get(path).get());
        assertEquals(java.util.List.of(1, 2, 3), jdbc.queryForList("SELECT attempt_number FROM webhook_delivery_attempts WHERE delivery_id = ? ORDER BY attempt_number", Integer.class, delivery.id()));
        for (int i = 1; i <= 3; i++) assertEquals(delivery.id(), deliveryIds.get(path + ":" + i));
        dispatcher.dispatch(event); // Simulate a repeated transport notification after success.
        assertEquals(1, deliveries.findByEventId(event.id()).size());
        assertEquals(3, calls.get(path).get());
    }

    @Test void deadLettersThenQueuesOperatorReplayWithoutLosingHistory() {
        String path = "/dead-" + UUID.randomUUID();
        successesAfter.put(path, 100);
        Event event = fixture("dead", path, 2);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(EventStatus.DEAD_LETTERED, events.findById(event.id()).orElseThrow().status()));
        WebhookDelivery delivery = deliveries.findByEventId(event.id()).getFirst();
        assertEquals(2, delivery.attemptNumber());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM event_outbox WHERE event_id = ? AND kind = 'DLQ'", Integer.class, event.id()));
        successesAfter.put(path, 0);
        dispatcher.queueReplay(delivery.id());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(EventStatus.DELIVERED, events.findById(event.id()).orElseThrow().status()));
        assertEquals(3, deliveries.findById(delivery.id()).orElseThrow().attemptNumber());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM webhook_delivery_attempts WHERE delivery_id = ?", Integer.class, delivery.id()));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.queueReplay(delivery.id()));
    }

    @Test void aggregatesAllSubscriptionsAfterOneRetrySucceeds() {
        String failing = "/aggregate-fail-" + UUID.randomUUID();
        String healed = "/aggregate-heal-" + UUID.randomUUID();
        successesAfter.put(failing, 100);
        successesAfter.put(healed, 1);
        String tenantId = "tenant_" + UUID.randomUUID();
        tenants.save(Tenant.of(tenantId, "Aggregate test", TenantTier.PRO));
        for (String path : java.util.List.of(failing, healed)) subscriptions.save(new Subscription("sub_" + UUID.randomUUID(), tenantId,
                url(path), "secret-test-secret", "aggregate", true, 2, 50, 100, 2.0, 1000, Instant.now()));
        Event event = Event.create("evt_" + UUID.randomUUID(), tenantId, "aggregate", null, "{}");
        transactions.executeWithoutResult(tx -> { events.save(event); bus.publish(event); });
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(EventStatus.PARTIALLY_DELIVERED, events.findById(event.id()).orElseThrow().status()));
        assertEquals(2, deliveries.findByEventId(event.id()).size());
    }

    @Test void concurrentWorkerCannotDispatchTheSameDueDeliveryTwice() throws Exception {
        String path = "/concurrent-" + UUID.randomUUID();
        Event event = fixture("concurrent", path, 3);
        // A second worker competes with the real Spring scheduler using the same persisted rows.
        EventBus direct = new com.relayhub.kafka.DirectDispatchEventBus(new org.springframework.beans.factory.support.StaticListableBeanFactory(java.util.Map.of("dispatcher", dispatcher)).getBeanProvider(WebhookDispatcher.class));
        var second = new DurableWorkScheduler(jdbc, transactions, direct, events, dispatcher);
        second.setLeaseWorker(leaseWorker);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one = pool.submit(second::publishPending);
            var two = pool.submit(second::deliverDue);
            one.get(); two.get();
        }
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(EventStatus.DELIVERED, events.findById(event.id()).orElseThrow().status()));
        assertEquals(1, calls.get(path).get());
        assertEquals(1, deliveries.findByEventId(event.id()).size());
    }

    @Test void neverFollowsRedirectsAndBoundsResponsePreview() {
        var client = new HttpWebhookClient();
        client.setTargetPolicy(new WebhookTargetPolicy("127.0.0.1", true));
        assertEquals(302, client.sendWebhook(url("/redirect"), "{}", "signature", "test", "test", 2000).statusCode());
        assertNull(calls.get("/forbidden"));
        var response = client.sendWebhook(url("/large"), "{}", "signature", "test", "test", 2000);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().length() < 1100);
        assertFalse(response.body().contains("\0"));
        client.close();
    }

    @Test void rollsBackUncommittedAttemptAndRecoversWithStableDeliveryId() {
        String path = "/rollback-" + UUID.randomUUID();
        String tenantId = "tenant_" + UUID.randomUUID();
        tenants.save(Tenant.of(tenantId, "Rollback window", TenantTier.PRO));
        var event = Event.create("evt_" + UUID.randomUUID(), tenantId, "rollback", null, "{}");
        events.save(event.withStatus(EventStatus.PROCESSING));
        var sub = new Subscription("sub_" + UUID.randomUUID(), tenantId, url(path), "secret-test-secret",
                "rollback", true, 3, 50, 100, 2.0, 1000, Instant.now());
        subscriptions.save(sub);
        String id = "del_" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO webhook_deliveries(id,event_id,subscription_id,tenant_id,attempt_number,status,next_retry_at)
                VALUES (?,?,?,?,0,'RETRYING', CURRENT_TIMESTAMP + INTERVAL '1 hour')
                """, id, event.id(), sub.id(), tenantId);
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(tx -> {
            dispatcher.retrySingleDelivery(id); // Receiver accepts, but the local transaction is lost.
            throw new IllegalStateException("Injected sender failure before commit");
        }));
        assertEquals(0, deliveries.findById(id).orElseThrow().attemptNumber());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM webhook_delivery_attempts WHERE delivery_id = ?", Integer.class, id));
        jdbc.update("UPDATE webhook_deliveries SET next_retry_at = CURRENT_TIMESTAMP WHERE id = ?", id);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(DeliveryStatus.SUCCESS, deliveries.findById(id).orElseThrow().status()));
        assertEquals(2, calls.get(path).get());
        assertEquals(deliveryIds.get(path + ":1"), deliveryIds.get(path + ":2"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM webhook_delivery_attempts WHERE delivery_id = ?", Integer.class, id));
    }
    @Autowired com.relayhub.dispatcher.DeliveryLeaseWorker leaseWorker;

    @Test void httpAttemptDoesNotHoldEventLockAndExpiredLeaseIsRecovered() throws Exception {
        String path = "/lease-" + UUID.randomUUID();
        String tenantId = "tenant_" + UUID.randomUUID();
        tenants.save(Tenant.of(tenantId, "Lease recovery", TenantTier.PRO));
        Event event = Event.create("evt_" + UUID.randomUUID(), tenantId, "lease", null, "{}");
        events.save(event.withStatus(EventStatus.PROCESSING));
        var sub = new Subscription("sub_" + UUID.randomUUID(), tenantId, url(path), "secret-test-secret",
                "lease", true, 3, 50, 100, 2.0, 1000, Instant.now());
        subscriptions.save(sub);
        String id = "del_" + UUID.randomUUID(), token = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO webhook_deliveries(id,event_id,subscription_id,tenant_id,attempt_number,status,next_retry_at,claim_token)
                VALUES (?,?,?,?,1,'IN_FLIGHT',CURRENT_TIMESTAMP - INTERVAL '1 minute',?)
                """, id, event.id(), sub.id(), tenantId, token);
        jdbc.update("INSERT INTO webhook_delivery_claims(token,delivery_id,attempt_number,lease_until) VALUES (?,?,1,CURRENT_TIMESTAMP - INTERVAL '1 minute')", token,id);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(DeliveryStatus.SUCCESS, deliveries.findById(id).orElseThrow().status()));
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT outcome FROM webhook_delivery_claims WHERE token=?", String.class,token));
        assertEquals(2, deliveries.findById(id).orElseThrow().attemptNumber());
    }

    @Test void databaseEventLockIsAvailableDuringSlowHttp() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes(); entered.countDown();
            try { release.await(4, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.sendResponseHeaders(200,2); exchange.getResponseBody().write("ok".getBytes()); exchange.close();
        });
        slow.start();
        try {
            String tenantId = "tenant_" + UUID.randomUUID();
            tenants.save(Tenant.of(tenantId,"Slow HTTP",TenantTier.PRO));
            Event event = Event.create("evt_" + UUID.randomUUID(), tenantId,"slow",null,"{}");
            events.save(event.withStatus(EventStatus.PROCESSING));
            var sub = new Subscription("sub_" + UUID.randomUUID(),tenantId,"http://127.0.0.1:"+slow.getAddress().getPort()+"/",
                    "secret-test-secret","slow",true,3,50,100,2.0,5000,Instant.now());
            subscriptions.save(sub);
            String id="del_"+UUID.randomUUID();
            deliveries.save(WebhookDelivery.initial(id,event.id(),sub.id(),tenantId));
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            transactions.executeWithoutResult(tx -> {
                jdbc.execute("SET LOCAL lock_timeout='500ms'");
                events.lock(event.id());
            });
            assertEquals(DeliveryStatus.IN_FLIGHT, deliveries.findById(id).orElseThrow().status());
            release.countDown();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(DeliveryStatus.SUCCESS,deliveries.findById(id).orElseThrow().status()));
        } finally { release.countDown(); slow.stop(0); }
    }

}
