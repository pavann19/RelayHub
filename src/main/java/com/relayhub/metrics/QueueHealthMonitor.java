package com.relayhub.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scrapes read cached values; database failures leave an observable stale timestamp. */
@Component
public class QueueHealthMonitor {
    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private final Map<String, Double> values = new ConcurrentHashMap<>();
    public QueueHealthMonitor(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc; this.registry = registry;
        for (String metric : new String[]{"outbox_pending", "outbox_quarantined", "delivery_pending", "delivery_inflight",
                "kafka_quarantined", "oldest_pending_seconds", "refresh_timestamp_seconds", "refresh_failures_total"}) {
            values.put(metric, 0.0);
            Gauge.builder("relayhub_queue_" + metric, values, map -> map.get(metric)).register(registry);
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void refresh() {
        try {
            values.put("outbox_pending", count("SELECT count(*) FROM event_outbox WHERE published_at IS NULL AND quarantined_at IS NULL"));
            values.put("outbox_quarantined", count("SELECT count(*) FROM event_outbox WHERE quarantined_at IS NOT NULL AND published_at IS NULL"));
            values.put("delivery_pending", count("SELECT count(*) FROM webhook_deliveries WHERE status IN ('PENDING','RETRYING')"));
            values.put("delivery_inflight", count("SELECT count(*) FROM webhook_deliveries WHERE status='IN_FLIGHT'"));
            values.put("kafka_quarantined", count("SELECT count(*) FROM kafka_quarantine"));
            values.put("oldest_pending_seconds", jdbc.queryForObject("""
                    SELECT COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN(created_at))),0)
                    FROM event_outbox WHERE published_at IS NULL AND quarantined_at IS NULL
                    """, Double.class));
            values.put("refresh_timestamp_seconds", System.currentTimeMillis() / 1000.0);
        } catch (RuntimeException error) {
            registry.counter("relayhub_queue_refresh_errors_total").increment();
            values.compute("refresh_failures_total", (key, count) -> count + 1);
        }
    }
    private double count(String sql) { return jdbc.queryForObject(sql, Long.class).doubleValue(); }
}
