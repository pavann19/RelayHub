package com.relayhub.model;

import java.time.Instant;

public record WebhookDelivery(
        String id,
        String eventId,
        String subscriptionId,
        String tenantId,
        int attemptNumber,
        DeliveryStatus status,
        Integer httpStatus,
        String requestHeaders,
        String responseHeaders,
        String responseBody,
        String errorMessage,
        long latencyMs,
        Instant nextRetryAt,
        Instant createdAt,
        Instant completedAt
) {
    public static WebhookDelivery initial(String id, String eventId, String subscriptionId, String tenantId) {
        return new WebhookDelivery(
                id,
                eventId,
                subscriptionId,
                tenantId,
                0,
                DeliveryStatus.PENDING,
                null,
                null,
                null,
                null,
                null,
                0L,
                null,
                Instant.now(),
                null
        );
    }

    public WebhookDelivery completeSuccess(int httpStatus, String reqHeaders, String respHeaders, String respBody, long latencyMs) {
        return new WebhookDelivery(
                id,
                eventId,
                subscriptionId,
                tenantId,
                attemptNumber,
                DeliveryStatus.SUCCESS,
                httpStatus,
                reqHeaders,
                respHeaders,
                respBody,
                null,
                latencyMs,
                null,
                createdAt,
                Instant.now()
        );
    }

    public WebhookDelivery retryScheduled(int httpStatus, String reqHeaders, String respHeaders, String respBody, String error, long latencyMs, Instant nextRetry) {
        return new WebhookDelivery(
                id,
                eventId,
                subscriptionId,
                tenantId,
                attemptNumber,
                DeliveryStatus.RETRYING,
                httpStatus > 0 ? httpStatus : null,
                reqHeaders,
                respHeaders,
                respBody,
                error,
                latencyMs,
                nextRetry,
                createdAt,
                null
        );
    }

    public WebhookDelivery deadLetter(int httpStatus, String reqHeaders, String respHeaders, String respBody, String error, long latencyMs) {
        return new WebhookDelivery(
                id,
                eventId,
                subscriptionId,
                tenantId,
                attemptNumber,
                DeliveryStatus.DEAD_LETTERED,
                httpStatus > 0 ? httpStatus : null,
                reqHeaders,
                respHeaders,
                respBody,
                error,
                latencyMs,
                null,
                createdAt,
                Instant.now()
        );
    }
}

