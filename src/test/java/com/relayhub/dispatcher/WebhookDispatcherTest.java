package com.relayhub.dispatcher;

import com.relayhub.kafka.EventBus;
import com.relayhub.metrics.RelayHubMetrics;
import com.relayhub.model.DeliveryStatus;
import com.relayhub.model.Event;
import com.relayhub.model.EventStatus;
import com.relayhub.model.Subscription;
import com.relayhub.model.WebhookDelivery;
import com.relayhub.repository.DeliveryRepository;
import com.relayhub.repository.EventRepository;
import com.relayhub.repository.SubscriptionRepository;
import com.relayhub.signing.WebhookSigner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookDispatcherTest {

    private FakeSubscriptionRepository subscriptionRepository;
    private FakeDeliveryRepository deliveryRepository;
    private FakeEventRepository eventRepository;
    private WebhookSigner signer;
    private RetryPolicyCalculator retryCalculator;
    private FakeHttpWebhookClient httpClient;
    private RelayHubMetrics metrics;
    private FakeEventBus eventBus;
    private WebhookDispatcher dispatcher;

    private final Event event = Event.create("evt_1", "tenant_test", "order.created", null, "{\"orderId\":123}");
    private final Subscription subscription = new Subscription(
            "sub_1", "tenant_test", "https://api.partner.com/webhook", "secret_123",
            "*", true, 3, 1000L, 10000L, 2.0, 5000, Instant.now()
    );

    @BeforeEach
    void setUp() {
        subscriptionRepository = new FakeSubscriptionRepository();
        deliveryRepository = new FakeDeliveryRepository();
        eventRepository = new FakeEventRepository();
        signer = new WebhookSigner();
        retryCalculator = new RetryPolicyCalculator();
        httpClient = new FakeHttpWebhookClient();
        metrics = new RelayHubMetrics(new SimpleMeterRegistry());
        eventBus = new FakeEventBus();

        subscriptionRepository.save(subscription);
        eventRepository.save(event);

        dispatcher = new WebhookDispatcher(
                subscriptionRepository,
                deliveryRepository,
                eventRepository,
                signer,
                retryCalculator,
                httpClient,
                metrics,
                eventBus
        );
    }

    @Test
    @DisplayName("Should mark delivery SUCCESS on downstream HTTP 200")
    void shouldMarkSuccessOn200() {
        httpClient.nextResponse = HttpResponseSummary.success(200, Map.of("Content-Type", "application/json"), "{\"ok\":true}", 45L);

        WebhookDelivery delivery = dispatcher.executeAttempt(event, subscription, 1, null);

        assertEquals(DeliveryStatus.SUCCESS, delivery.status());
        assertEquals(200, delivery.httpStatus());
        assertNotNull(deliveryRepository.findById(delivery.id()));
    }

    @Test
    @DisplayName("Should schedule RETRY on downstream HTTP 429 rate limit")
    void shouldScheduleRetryOn429() {
        httpClient.nextResponse = HttpResponseSummary.success(429, Map.of("Retry-After", "30"), "Too Many Requests", 20L);

        WebhookDelivery delivery = dispatcher.executeAttempt(event, subscription, 1, null);

        assertEquals(DeliveryStatus.RETRYING, delivery.status());
        assertEquals(429, delivery.httpStatus());
        assertNotNull(delivery.nextRetryAt(), "Should schedule next retry timestamp");
    }

    @Test
    @DisplayName("Should immediately DEAD_LETTER on downstream non-retryable 4xx client error (e.g. 404)")
    void shouldDeadLetterOn4xxClientError() {
        httpClient.nextResponse = HttpResponseSummary.success(404, Map.of(), "Not Found", 15L);

        WebhookDelivery delivery = dispatcher.executeAttempt(event, subscription, 1, null);

        assertEquals(DeliveryStatus.DEAD_LETTERED, delivery.status());
        assertEquals(404, delivery.httpStatus());
        assertTrue(eventBus.dlqPublished.contains(event.id()), "Must publish to DLQ");
        assertEquals(1, eventBus.dlqPublished.size());
    }

    @Test
    @DisplayName("Should schedule RETRY on downstream HTTP 500 server error when attempts < maxRetries")
    void shouldScheduleRetryOn500() {
        httpClient.nextResponse = HttpResponseSummary.success(500, Map.of(), "Internal Server Error", 35L);

        WebhookDelivery delivery = dispatcher.executeAttempt(event, subscription, 1, null);

        assertEquals(DeliveryStatus.RETRYING, delivery.status());
        assertEquals(500, delivery.httpStatus());
        assertNotNull(delivery.nextRetryAt());
    }

    @Test
    @DisplayName("Should DEAD_LETTER when retries reach maxRetries limit")
    void shouldDeadLetterWhenMaxRetriesExceeded() {
        httpClient.nextResponse = HttpResponseSummary.success(503, Map.of(), "Service Unavailable", 100L);

        // Attempt 3 equals maxRetries (3)
        WebhookDelivery delivery = dispatcher.executeAttempt(event, subscription, 3, null);

        assertEquals(DeliveryStatus.DEAD_LETTERED, delivery.status());
        assertTrue(eventBus.dlqPublished.contains(event.id()), "Must publish to DLQ on max retries");
    }

    @Test
    void computesClientFailureWithoutPublishingOrPersistingUntilFinalization() {
        httpClient.nextResponse = HttpResponseSummary.success(404, Map.of(), "Not Found", 1);
        WebhookDelivery computed = dispatcher.computeAttempt(event, subscription, 1, null);
        assertEquals(DeliveryStatus.DEAD_LETTERED, computed.status());
        assertTrue(eventBus.dlqPublished.isEmpty());
        assertTrue(deliveryRepository.map.isEmpty());
        dispatcher.persistAttempt(event, computed);
        assertEquals(1, eventBus.dlqPublished.size());
    }

    private static class FakeHttpWebhookClient extends HttpWebhookClient {
        HttpResponseSummary nextResponse;

        @Override
        public HttpResponseSummary sendWebhook(String url, String payload, String sig, String eventType, String delId, int timeout) {
            return nextResponse != null ? nextResponse : HttpResponseSummary.success(200, Map.of(), "{}", 10L);
        }
    }

    private static class FakeEventBus implements EventBus {
        final List<String> published = new ArrayList<>();
        final List<String> dlqPublished = new ArrayList<>();

        @Override
        public void publish(Event event) {
            published.add(event.id());
        }

        @Override
        public void publishDlq(Event event, String reason) {
            dlqPublished.add(event.id());
        }
    }

    private static class FakeSubscriptionRepository extends SubscriptionRepository {
        final Map<String, Subscription> map = new ConcurrentHashMap<>();

        public FakeSubscriptionRepository() {
            super(null);
        }

        @Override
        public Optional<Subscription> findById(String id) {
            return Optional.ofNullable(map.get(id));
        }

        @Override
        public List<Subscription> findActiveByTenantId(String tenantId) {
            return map.values().stream().filter(s -> s.tenantId().equals(tenantId) && s.isActive()).toList();
        }

        @Override
        public Subscription save(Subscription sub) {
            map.put(sub.id(), sub);
            return sub;
        }
    }

    private static class FakeDeliveryRepository extends DeliveryRepository {
        final Map<String, WebhookDelivery> map = new ConcurrentHashMap<>();

        public FakeDeliveryRepository() {
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

    private static class FakeEventRepository extends EventRepository {
        final Map<String, Event> map = new ConcurrentHashMap<>();

        public FakeEventRepository() {
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
            Event current = map.get(id);
            if (current != null) {
                map.put(id, current.withStatus(status));
            }
        }
    }
}
