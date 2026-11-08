package com.relayhub.dispatcher;

import com.relayhub.kafka.EventBus;
import com.relayhub.repository.EventRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Row locks serialize workers across replicas and are released on process death. */
@Component
@ConditionalOnProperty(name = "relayhub.workers.enabled", havingValue = "true", matchIfMissing = true)
public class DurableWorkScheduler {
    private static final Logger log = LoggerFactory.getLogger(DurableWorkScheduler.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EventBus transport;
    private final EventRepository events;
    private final WebhookDispatcher dispatcher;
    private DeliveryLeaseWorker leaseWorker;
    private io.micrometer.core.instrument.MeterRegistry registry;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRegistry(io.micrometer.core.instrument.MeterRegistry value) { this.registry = value; }
    private void recordFailure(String worker) {
        if (registry != null) registry.counter("relayhub_worker_failures_total", "worker", worker).increment();
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setLeaseWorker(DeliveryLeaseWorker worker) { this.leaseWorker = worker; }

    public DurableWorkScheduler(JdbcTemplate jdbc, TransactionTemplate transactions,
            @Qualifier("transportEventBus") EventBus transport, EventRepository events,
            WebhookDispatcher dispatcher) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.transport = transport;
        this.events = events;
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${relayhub.workers.poll-ms:500}")
    public void publishPending() {
        for (int i = 0; i < 25; i++) {
            long[] selected = {-1};
            try {
                Boolean processed = transactions.execute(tx -> {
                    List<OutboxRow> rows = jdbc.query("""
                            SELECT id, event_id, kind, reason FROM event_outbox
                            WHERE published_at IS NULL AND quarantined_at IS NULL AND next_attempt_at <= CURRENT_TIMESTAMP ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                            """, (rs, n) -> new OutboxRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                    if (rows.isEmpty()) return false;
                    OutboxRow row = rows.getFirst();
                    selected[0] = row.id();
                    var event = events.findById(row.eventId()).orElseThrow();
                    if (row.kind().equals("EVENT")) transport.publish(event);
                    else transport.publishDlq(event, row.reason());
                    jdbc.update("UPDATE event_outbox SET published_at = CURRENT_TIMESTAMP WHERE id = ?", row.id());
                    return true;
                });
                if (!Boolean.TRUE.equals(processed)) break;
            } catch (Exception e) {
                recordFailure("outbox");
                if (selected[0] < 0) { log.warn("Outbox selection failed", e); break; }
                jdbc.update("""
                        UPDATE event_outbox SET failure_count = failure_count + 1,
                        next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '30 seconds',
                        quarantined_at = CASE WHEN failure_count >= 4 THEN CURRENT_TIMESTAMP ELSE NULL END,
                        last_error = ? WHERE id = ? AND published_at IS NULL
                        """, e.getClass().getSimpleName(), selected[0]);
                log.warn("Outbox item {} deferred or quarantined; other work can continue", selected[0], e);
            }
        }
    }

    @Scheduled(fixedDelayString = "${relayhub.workers.poll-ms:500}")
    public void deliverDue() {
        for (int i = 0; i < 25; i++) {
            String[] selected = {null};
            try {
                List<String> ids = jdbc.queryForList("""
                        SELECT id FROM webhook_deliveries WHERE status = 'PENDING'
                        OR (status IN ('RETRYING', 'IN_FLIGHT') AND next_retry_at <= CURRENT_TIMESTAMP)
                        ORDER BY COALESCE(next_retry_at, created_at), id LIMIT 1
                        """, String.class);
                if (ids.isEmpty()) break;
                selected[0] = ids.getFirst();
                if (leaseWorker == null) throw new IllegalStateException("Delivery lease worker is required");
                leaseWorker.process(selected[0]);
            } catch (Exception e) {
                recordFailure("delivery");
                if (selected[0] == null) { log.warn("Delivery selection failed", e); break; }
                jdbc.update("""
                        UPDATE webhook_deliveries SET status = 'RETRYING',
                        next_retry_at = CURRENT_TIMESTAMP + INTERVAL '30 seconds', error_message = ?
                        WHERE id = ? AND status IN ('PENDING', 'RETRYING')
                        """, "Worker transaction failed: " + e.getClass().getSimpleName(), selected[0]);
                log.warn("Delivery {} deferred; other work can continue", selected[0], e);
            }
        }
    }

    private record OutboxRow(long id, String eventId, String kind, String reason) {}
}
