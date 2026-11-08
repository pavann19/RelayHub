package com.relayhub.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class RelayHubMetrics {

    private final MeterRegistry registry;

    public RelayHubMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordGatewayRequest(String tenantId, String method, int statusCode) {
        Counter.builder("relayhub_gateway_requests_total")
                .description("Total number of ingress API requests handled by RelayHub Gateway")
                .tag("method", method)
                .tag("status", String.valueOf(statusCode))
                .register(registry)
                .increment();
    }

    public void recordRateLimitExceeded(String tenantId) {
        Counter.builder("relayhub_ratelimit_exceeded_total")
                .description("Total number of requests rejected due to rate limiting or quotas")
                .register(registry)
                .increment();
    }

    public void recordWebhookDelivery(String tenantId, String subscriptionId, int httpStatus, String outcome) {
        Counter.builder("relayhub_webhook_deliveries_total")
                .description("Total webhook dispatch attempts")
                .tag("status", String.valueOf(httpStatus))
                .tag("outcome", outcome) // SUCCESS, RETRY, DEAD_LETTER
                .register(registry)
                .increment();
    }

    public void recordDeliveryLatency(String tenantId, String subscriptionId, long latencyMs) {
        Timer.builder("relayhub_webhook_delivery_latency_seconds")
                .description("Webhook dispatch roundtrip latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    public void recordDlqEvent(String tenantId, String reason) {
        Counter.builder("relayhub_dlq_events_total")
                .description("Total events sent to Dead-Letter Queue")
                .tag("reason", reason != null ? reason : "unknown")
                .register(registry)
                .increment();
    }
}

