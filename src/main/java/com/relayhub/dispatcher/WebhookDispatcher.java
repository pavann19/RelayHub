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
import java.util.List;
import java.util.UUID;
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

    @org.springframework.transaction.annotation.Transactional
    public List<WebhookDelivery> dispatch(Event event) {
        eventRepository.lock(event.id());
        List<WebhookDelivery> existing = deliveryRepository.findByEventId(event.id());
        if (eventRepository.findById(event.id()).orElseThrow().status() != EventStatus.PENDING) return existing;
        List<Subscription> matching = subscriptionRepository.findActiveByTenantId(event.tenantId()).stream()
                .filter(sub -> sub.matchesEventType(event.eventType())).toList();
        for (Subscription sub : matching) {
            if (existing.stream().noneMatch(d -> d.subscriptionId().equals(sub.id()))) {
                deliveryRepository.save(WebhookDelivery.initial("del_" + UUID.randomUUID(),
                        event.id(), sub.id(), event.tenantId()));
            }
        }
        List<WebhookDelivery> deliveries = deliveryRepository.findByEventId(event.id());
        if (deliveries.isEmpty()) eventRepository.updateStatus(event.id(), EventStatus.DELIVERED);
        else updateAggregateEventStatus(event.id(), deliveries);
        return deliveries;
    }

    @org.springframework.transaction.annotation.Transactional
    public WebhookDelivery executeAttempt(Event event, Subscription sub, int attemptNumber, String existingDeliveryId) {
        WebhookDelivery delivery = computeAttempt(event, sub, attemptNumber, existingDeliveryId);
        persistAttempt(event, delivery);
        return delivery;
    }

    public WebhookDelivery computeAttempt(Event event, Subscription sub, int attemptNumber, String existingDeliveryId) {
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
                log.error("Webhook delivery {} dead-lettered after {} attempts. Last error: {}", deliveryId, attemptNumber, errorMsg);
            }
        }

        return delivery;
    }

    public void persistAttempt(Event event, WebhookDelivery delivery) {
        deliveryRepository.save(delivery);
        if (delivery.status() == DeliveryStatus.DEAD_LETTERED) eventBus.publishDlq(event, delivery.errorMessage());
        updateAggregateEventStatus(event.id(), deliveryRepository.findByEventId(event.id()));
    }

    @org.springframework.transaction.annotation.Transactional
    public WebhookDelivery retrySingleDelivery(String deliveryId) {
        WebhookDelivery oldDelivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found: " + deliveryId));

        eventRepository.lock(oldDelivery.eventId());
        oldDelivery = deliveryRepository.findById(deliveryId).orElseThrow();
        Event event = eventRepository.findById(oldDelivery.eventId())
                .orElseThrow(() -> new IllegalArgumentException("Event not found"));

        Subscription sub = subscriptionRepository.findById(oldDelivery.subscriptionId())
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found"));

        if (!sub.isActive()) {
            WebhookDelivery cancelled = new WebhookDelivery(oldDelivery.id(), oldDelivery.eventId(),
                    oldDelivery.subscriptionId(), oldDelivery.tenantId(), oldDelivery.attemptNumber(),
                    DeliveryStatus.CANCELLED, oldDelivery.httpStatus(), oldDelivery.requestHeaders(),
                    oldDelivery.responseHeaders(), oldDelivery.responseBody(), "Subscription disabled",
                    oldDelivery.latencyMs(), null, oldDelivery.createdAt(), Instant.now());
            deliveryRepository.save(cancelled);
            updateAggregateEventStatus(event.id(), deliveryRepository.findByEventId(event.id()));
            return cancelled;
        }
        log.info("Retrying delivery {} (current attempt: {})", deliveryId, oldDelivery.attemptNumber() + 1);
        WebhookDelivery newDelivery = executeAttempt(event, sub, oldDelivery.status() == DeliveryStatus.PENDING ? 1 : oldDelivery.attemptNumber() + 1, deliveryId);
        updateAggregateEventStatus(event.id(), deliveryRepository.findByEventId(event.id()));
        return newDelivery;
    }

    @org.springframework.transaction.annotation.Transactional
    public void queueReplay(String deliveryId) {
        WebhookDelivery old = deliveryRepository.findById(deliveryId).orElseThrow();
        eventRepository.lock(old.eventId());
        old = deliveryRepository.findById(deliveryId).orElseThrow();
        if (!subscriptionRepository.findById(old.subscriptionId()).orElseThrow().isActive()) {
            throw new IllegalArgumentException("Disabled subscriptions cannot be replayed");
        }
        if (old.status() != DeliveryStatus.DEAD_LETTERED && old.status() != DeliveryStatus.FAILED) {
            throw new IllegalArgumentException("Only failed or dead-lettered deliveries can be replayed");
        }
        deliveryRepository.save(new WebhookDelivery(old.id(), old.eventId(), old.subscriptionId(), old.tenantId(),
                old.attemptNumber(), DeliveryStatus.RETRYING, old.httpStatus(), old.requestHeaders(),
                old.responseHeaders(), old.responseBody(), old.errorMessage(), old.latencyMs(),
                Instant.now(), old.createdAt(), null));
        updateAggregateEventStatus(old.eventId(), deliveryRepository.findByEventId(old.eventId()));
    }

    private void updateAggregateEventStatus(String eventId, List<WebhookDelivery> deliveries) {
        if (deliveries.isEmpty()) {
            return;
        }

        boolean anyRetrying = deliveries.stream().anyMatch(d -> d.status() == DeliveryStatus.RETRYING || d.status() == DeliveryStatus.PENDING || d.status() == DeliveryStatus.IN_FLIGHT);
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

