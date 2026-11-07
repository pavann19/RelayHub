package com.relayhub.dispatcher;

import com.relayhub.kafka.EventBus;
import com.relayhub.metrics.RelayHubMetrics;
import com.relayhub.model.DeliveryStatus;
import com.relayhub.model.Event;
import com.relayhub.model.EventStatus;
import com.relayhub.model.Subscription;
import com.relayhub.model.Tenant;
import com.relayhub.model.TenantTier;
import com.relayhub.model.WebhookDelivery;
import com.relayhub.ratelimit.InMemoryTokenBucketRateLimiter;
import com.relayhub.repository.DeliveryRepository;
import com.relayhub.repository.EventRepository;
import com.relayhub.repository.SubscriptionRepository;
import com.relayhub.signing.WebhookSigner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureInjectionAndRecoveryTest {

    private InMemoryDeliveryRepository deliveryRepository;
    private InMemoryEventRepository eventRepository;
    private InMemorySubscriptionRepository subscriptionRepository;
    private FlakyHttpWebhookClient flakyClient;
    private WebhookDispatcher dispatcher;
    private FakeEventBus eventBus;

    private final Subscription subscription = new Subscription(
            "sub_flaky", "tenant_resilience", "https://flaky-service.internal/webhook", "secret_resilience",
            "*", true, 5, 100L, 1000L, 2.0, 5000, Instant.now()
    );

    @BeforeEach
    void setUp() {
        deliveryRepository = new InMemoryDeliveryRepository();
        eventRepository = new InMemoryEventRepository();
        subscriptionRepository = new InMemorySubscriptionRepository();
        subscriptionRepository.save(subscription);

        flakyClient = new FlakyHttpWebhookClient();
        eventBus = new FakeEventBus();
        RelayHubMetrics metrics = new RelayHubMetrics(new SimpleMeterRegistry());
        RetryPolicyCalculator retryCalculator = new RetryPolicyCalculator();
        WebhookSigner signer = new WebhookSigner();

        dispatcher = new WebhookDispatcher(
                subscriptionRepository,
                deliveryRepository,
                eventRepository,
                signer,
                retryCalculator,
                flakyClient,
                metrics,
                eventBus
        );
    }

    @Test
    @DisplayName("Should recover and succeed when downstream fails with 503 then heals on 3rd attempt")
    void shouldRecoverFromTemporaryDownstreamFailure() {
        Event event = Event.create("evt_recover_1", "tenant_resilience", "payment.created", null, "{\"id\":1}");
        eventRepository.save(event);

        // Configure downstream to fail twice with 503, then recover with 200
        flakyClient.setFailureCountBeforeSuccess(2, 503, "Service Temporarily Unavailable");

        // Attempt 1: 503 -> RETRYING
        WebhookDelivery attempt1 = dispatcher.executeAttempt(event, subscription, 1, null);
        assertEquals(DeliveryStatus.RETRYING, attempt1.status());
        assertEquals(503, attempt1.httpStatus());

        // Attempt 2: 503 -> RETRYING
        WebhookDelivery attempt2 = dispatcher.executeAttempt(event, subscription, 2, attempt1.id());
        assertEquals(DeliveryStatus.RETRYING, attempt2.status());
        assertEquals(503, attempt2.httpStatus());

        // Attempt 3: Healed -> SUCCESS
        WebhookDelivery attempt3 = dispatcher.executeAttempt(event, subscription, 3, attempt1.id());
        assertEquals(DeliveryStatus.SUCCESS, attempt3.status());
        assertEquals(200, attempt3.httpStatus());
        assertNotNull(attempt3.completedAt());
    }

    @Test
    @DisplayName("Should dead-letter after exceeding max attempts and successfully replay on demand")
    void shouldDeadLetterAndReplaySuccessfully() {
        Event event = Event.create("evt_dlq_1", "tenant_resilience", "payment.failed", null, "{\"id\":2}");
        eventRepository.save(event);

        // Downstream persistently fails
        flakyClient.setFailureCountBeforeSuccess(999, 500, "Persistent Internal Server Error");

        // Execute until max retries (5)
        WebhookDelivery delivery = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            delivery = dispatcher.executeAttempt(event, subscription, attempt, delivery != null ? delivery.id() : null);
        }

        assertNotNull(delivery);
        assertEquals(DeliveryStatus.DEAD_LETTERED, delivery.status());
        assertEquals(5, delivery.attemptNumber());
        assertTrue(eventBus.dlqEvents.contains(event.id()), "Event must be in DLQ");

        // Simulate operator fixing the downstream service and triggering Replay API
        flakyClient.setFailureCountBeforeSuccess(0, 200, "OK"); // Healed!

        WebhookDelivery replayed = dispatcher.retrySingleDelivery(delivery.id());
        assertEquals(DeliveryStatus.SUCCESS, replayed.status());
        assertEquals(200, replayed.httpStatus());
    }

    @Test
    @DisplayName("Should maintain consistency under concurrent multi-threaded event ingestion and rate limits")
    void shouldMaintainConsistencyUnderHighConcurrency() throws InterruptedException {
        int threadCount = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        InMemoryTokenBucketRateLimiter rateLimiter = new InMemoryTokenBucketRateLimiter();
        // Rate: 1 token/sec (prevents sub-millisecond refill), Burst: 20
        Tenant testTenant = new Tenant("tenant_concurrent", "Concurrent Tenant", TenantTier.PRO, 1, 20, 10000L, null);

        AtomicInteger allowedCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    var res = rateLimiter.tryConsume(testTenant, 1);
                    if (res.allowed()) {
                        allowedCount.incrementAndGet();
                    } else {
                        rejectedCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();

        // Burst is 20: exactly 20 allowed, exactly 20 rejected
        assertEquals(20, allowedCount.get(), "Allowed count must match burst capacity exactly");
        assertEquals(20, rejectedCount.get(), "Exceeded requests must be rejected with 429 semantics");
        assertEquals(threadCount, allowedCount.get() + rejectedCount.get());
    }

    private static class FlakyHttpWebhookClient extends HttpWebhookClient {
        private int remainingFailures = 0;
        private int failureStatusCode = 500;
        private String failureMessage = "Server Error";

        public void setFailureCountBeforeSuccess(int count, int statusCode, String message) {
            this.remainingFailures = count;
            this.failureStatusCode = statusCode;
            this.failureMessage = message;
        }

        @Override
        public HttpResponseSummary sendWebhook(String url, String payload, String sig, String eventType, String delId, int timeout) {
            if (remainingFailures > 0) {
                remainingFailures--;
                return HttpResponseSummary.error(failureStatusCode, failureMessage, 25L);
            }
            return HttpResponseSummary.success(200, Map.of("Content-Type", "application/json"), "{\"status\":\"delivered\"}", 15L);
        }
    }

    private static class FakeEventBus implements EventBus {
        final List<String> dlqEvents = new ArrayList<>();

        @Override
        public void publish(Event event) {}

        @Override
        public void publishDlq(Event event, String reason) {
            dlqEvents.add(event.id());
        }
    }

    private static class InMemorySubscriptionRepository extends SubscriptionRepository {
        final Map<String, Subscription> map = new ConcurrentHashMap<>();

        public InMemorySubscriptionRepository() {
            super(null);
        }

        @Override
        public Optional<Subscription> findById(String id) {
            return Optional.ofNullable(map.get(id));
        }

        @Override
        public Subscription save(Subscription sub) {
            map.put(sub.id(), sub);
            return sub;
        }
    }

    private static class InMemoryDeliveryRepository extends DeliveryRepository {
        final Map<String, WebhookDelivery> map = new ConcurrentHashMap<>();

        public InMemoryDeliveryRepository() {
            super(null);
        }

        @Override
        public List<WebhookDelivery> findByEventId(String eventId) {
            return map.values().stream().filter(d -> d.eventId().equals(eventId)).toList();
        }

        @Override
        public Optional<WebhookDelivery> findById(String id) {
            return Optional.ofNullable(map.get(id));
        }

        @Override
        public WebhookDelivery save(WebhookDelivery d) {
            map.put(d.id(), d);
            return d;
        }
    }

    private static class InMemoryEventRepository extends EventRepository {
        final Map<String, Event> map = new ConcurrentHashMap<>();

        public InMemoryEventRepository() {
            super(null);
        }

        @Override
        public Optional<Event> findById(String id) {
            return Optional.ofNullable(map.get(id));
        }

        @Override
        public Event save(Event event) {
            map.put(event.id(), event);
            return event;
        }

        @Override
        public void lock(String id) {}

        @Override
        public void updateStatus(String id, EventStatus status) {
            Event cur = map.get(id);
            if (cur != null) {
                map.put(id, cur.withStatus(status));
            }
        }
    }
}
