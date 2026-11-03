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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final SubscriptionRepository subscriptionRepository;
    private final DeliveryRepository deliveryRepository;
    private final EventRepository eventRepository;
    private final WebhookSigner signer;
    private final RetryPolicyCalculator retryCalculator;
    private final HttpWebhookClient httpClient;
    private final RelayHubMetrics metrics;
    private final EventBus eventBus;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public WebhookDispatcher(
            SubscriptionRepository subscriptionRepository,
            DeliveryRepository deliveryRepository,
            EventRepository eventRepository,
            WebhookSigner signer,
            RetryPolicyCalculator retryCalculator,
            HttpWebhookClient httpClient,
            RelayHubMetrics metrics,
            EventBus eventBus
    ) {
        this.subscriptionRepository = subscriptionRepository;
        this.deliveryRepository = deliveryRepository;
        this.eventRepository = eventRepository;
        this.signer = signer;
        this.retryCalculator = retryCalculator;
        this.httpClient = httpClient;
        this.metrics = metrics;
        this.eventBus = eventBus;
    }

    public List<WebhookDelivery> dispatch(Event event) {
        List<Subscription> subscriptions = subscriptionRepository.findActiveByTenantId(event.tenantId());
        List<Subscription> matching = subscriptions.stream()
                .filter(s -> s.matchesEventType(event.eventType()))
                .toList();

        if (matching.isEmpty()) {
            log.info("No active subscriptions matched for event {} (type: {})", event.id(), event.eventType());
            eventRepository.updateStatus(event.id(), EventStatus.DELIVERED);
            return List.of();
        }

        List<CompletableFuture<WebhookDelivery>> futures = new ArrayList<>();
        for (Subscription sub : matching) {
            futures.add(CompletableFuture.supplyAsync(() -> executeAttempt(event, sub, 1, null), executor));
        }

        List<WebhookDelivery> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        updateAggregateEventStatus(event.id(), results);
        return results;
    }

    public WebhookDelivery executeAttempt(Event event, Subscription sub, int attemptNumber, String existingDeliveryId) {
        String deliveryId = existingDeliveryId != null ? existingDeliveryId : "del_" + UUID.randomUUID();
        String signatureHeader = signer.generateSignatureHeader(event.payload(), sub.secret());

        log.debug("Dispatching webhook delivery {} for event {} to {}", deliveryId, event.id(), sub.targetUrl());

        HttpResponseSummary response = httpClient.sendWebhook(
                sub.targetUrl(),
                event.payload(),
                signatureHeader,
                event.eventType(),
                deliveryId,
                sub.timeoutMs()
        );

        int status = response.statusCode();
        long latencyMs = response.latencyMs();
        String reqHeaders = "X-RelayHub-Signature: " + signatureHeader + "\nX-RelayHub-Event: " + event.eventType();
        String respHeaders = response.headers().toString();
        String respBody = response.body();

        metrics.recordDeliveryLatency(event.tenantId(), sub.id(), latencyMs);

        WebhookDelivery delivery;

        // 1. HTTP 2xx: Success
        if (status >= 200 && status < 300) {
            delivery = new WebhookDelivery(
                    deliveryId,
                    event.id(),
                    sub.id(),
                    event.tenantId(),
                    attemptNumber,
                    DeliveryStatus.SUCCESS,
                    status,
                    reqHeaders,
                    respHeaders,
                    respBody,
                    null,
                    latencyMs,
                    null,
                    Instant.now(),
                    Instant.now()
            );
            metrics.recordWebhookDelivery(event.tenantId(), sub.id(), status, "SUCCESS");
            log.info("Webhook delivery {} succeeded with HTTP {}", deliveryId, status);
        }
        // 2. HTTP 429: Downstream Rate Limited
        else if (status == 429) {
            String retryAfterHeader = response.headers().get("Retry-After");
            if (retryAfterHeader == null) {
                retryAfterHeader = response.headers().get("retry-after");
            }

            if (attemptNumber < sub.maxRetries()) {
                Instant nextRetry = retryCalculator.calculateNextRetry(sub, attemptNumber, retryAfterHeader);
                delivery = new WebhookDelivery(
                        deliveryId,
                        event.id(),
                        sub.id(),
                        event.tenantId(),
                        attemptNumber,
                        DeliveryStatus.RETRYING,
                        status,
                        reqHeaders,
                        respHeaders,
                        respBody,
                        "Downstream returned 429 Too Many Requests (Retry-After: " + retryAfterHeader + ")",
                        latencyMs,
                        nextRetry,
                        Instant.now(),
                        null
                );
                metrics.recordWebhookDelivery(event.tenantId(), sub.id(), status, "RETRY");
                log.warn("Webhook delivery {} hit 429 rate limit. Retry scheduled for {}", deliveryId, nextRetry);
            } else {
                delivery = new WebhookDelivery(
                        deliveryId,
                        event.id(),
                        sub.id(),
                        event.tenantId(),
                        attemptNumber,
                        DeliveryStatus.DEAD_LETTERED,
                        status,
                        reqHeaders,
                        respHeaders,
                        respBody,
                        "Exhausted max retries (" + sub.maxRetries() + ") following 429 rate limit",
                        latencyMs,
                        null,
                        Instant.now(),
                        Instant.now()
                );
                metrics.recordWebhookDelivery(event.tenantId(), sub.id(), status, "DEAD_LETTER");
                metrics.recordDlqEvent(event.tenantId(), "429_RETRIES_EXHAUSTED");
                eventBus.publishDlq(event, "Downstream 429 retries exhausted");
                log.error("Webhook delivery {} dead-lettered after {} attempts (HTTP 429)", deliveryId, attemptNumber);
            }
        }
        // 3. HTTP 4xx (non-retryable client errors e.g. 400, 401, 403, 404)
        else if (status >= 400 && status < 500) {
            delivery = new WebhookDelivery(
                    deliveryId,
                    event.id(),
                    sub.id(),
                    event.tenantId(),
                    attemptNumber,
                    DeliveryStatus.DEAD_LETTERED,
                    status,
                    reqHeaders,
                    respHeaders,
                    respBody,
                    "Downstream client error HTTP " + status + " is non-retryable",
                    latencyMs,
                    null,
                    Instant.now(),
                    Instant.now()
            );
            metrics.recordWebhookDelivery(event.tenantId(), sub.id(), status, "DEAD_LETTER");
            metrics.recordDlqEvent(event.tenantId(), "CLIENT_ERROR_" + status);
            eventBus.publishDlq(event, "Downstream non-retryable HTTP " + status);
            log.warn("Webhook delivery {} immediately dead-lettered due to non-retryable client error HTTP {}", deliveryId, status);
        }
        // 4. HTTP 5xx or Connection Failure (status == 0)
        else {
            String errorMsg = response.errorMessage() != null ? response.errorMessage() : "Downstream HTTP " + status;
            if (attemptNumber < sub.maxRetries()) {
                Instant nextRetry = retryCalculator.calculateNextRetry(sub, attemptNumber, null);
                delivery = new WebhookDelivery(
                        deliveryId,
                        event.id(),
                        sub.id(),
                        event.tenantId(),
                        attemptNumber,
                        DeliveryStatus.RETRYING,
                        status > 0 ? status : null,
                        reqHeaders,
                        respHeaders,
                        respBody,
                        errorMsg,
                        latencyMs,
                        nextRetry,
                        Instant.now(),
                        null
                );
                metrics.recordWebhookDelivery(event.tenantId(), sub.id(), status, "RETRY");
                log.warn("Webhook delivery {} failed with {}. Retry scheduled for {}", deliveryId, errorMsg, nextRetry);
            } else {
                delivery = new WebhookDelivery(
                        deliveryId,
                        event.id(),
                        sub.id(),
                        event.tenantId(),
                        attemptNumber,
                        DeliveryStatus.DEAD_LETTERED,
                        status > 0 ? status : null,
                        reqHeaders,
                        respHeaders,
                        respBody,
                        "Exhausted max retries (" + sub.maxRetries() + "). Last error: " + errorMsg,
                        latencyMs,
                        null,
                        Instant.now(),
                        Instant.now()
                );
                metrics.recordWebhookDelivery(event.tenantId(), sub.id(), status, "DEAD_LETTER");
                metrics.recordDlqEvent(event.tenantId(), "SERVER_ERROR_RETRIES_EXHAUSTED");
                eventBus.publishDlq(event, "Downstream 5xx/network errors exhausted max retries");
                log.error("Webhook delivery {} dead-lettered after {} attempts. Last error: {}", deliveryId, attemptNumber, errorMsg);
            }
        }

        deliveryRepository.save(delivery);
        return delivery;
    }

    public WebhookDelivery retrySingleDelivery(String deliveryId) {
        WebhookDelivery oldDelivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found: " + deliveryId));

        Event event = eventRepository.findById(oldDelivery.eventId())
                .orElseThrow(() -> new IllegalArgumentException("Event not found: " + oldDelivery.eventId()));

        Subscription sub = subscriptionRepository.findById(oldDelivery.subscriptionId())
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found: " + oldDelivery.subscriptionId()));

        log.info("Retrying delivery {} (current attempt: {})", deliveryId, oldDelivery.attemptNumber() + 1);
        WebhookDelivery newDelivery = executeAttempt(event, sub, oldDelivery.attemptNumber() + 1, deliveryId);
        updateAggregateEventStatus(event.id(), List.of(newDelivery));
        return newDelivery;
    }

    private void updateAggregateEventStatus(String eventId, List<WebhookDelivery> deliveries) {
        if (deliveries.isEmpty()) {
            return;
        }

        boolean anyRetrying = deliveries.stream().anyMatch(d -> d.status() == DeliveryStatus.RETRYING);
        boolean allSuccess = deliveries.stream().allMatch(d -> d.status() == DeliveryStatus.SUCCESS);
        boolean anySuccess = deliveries.stream().anyMatch(d -> d.status() == DeliveryStatus.SUCCESS);
        boolean allDead = deliveries.stream().allMatch(d -> d.status() == DeliveryStatus.DEAD_LETTERED);

        EventStatus newStatus;
        if (allSuccess) {
            newStatus = EventStatus.DELIVERED;
        } else if (anyRetrying) {
            newStatus = EventStatus.PROCESSING;
        } else if (allDead) {
            newStatus = EventStatus.DEAD_LETTERED;
        } else if (anySuccess) {
            newStatus = EventStatus.PARTIALLY_DELIVERED;
        } else {
            newStatus = EventStatus.FAILED;
        }

        eventRepository.updateStatus(eventId, newStatus);
    }
}

