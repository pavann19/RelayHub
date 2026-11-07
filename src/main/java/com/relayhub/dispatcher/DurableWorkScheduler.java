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
            try {
                Boolean processed = transactions.execute(tx -> {
                    List<OutboxRow> rows = jdbc.query("""
                            SELECT id, event_id, kind, reason FROM event_outbox
                            WHERE published_at IS NULL ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                            """, (rs, n) -> new OutboxRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                    if (rows.isEmpty()) return false;
                    OutboxRow row = rows.getFirst();
                    var event = events.findById(row.eventId()).orElseThrow();
                    if (row.kind().equals("EVENT")) transport.publish(event);
                    else transport.publishDlq(event, row.reason());
                    jdbc.update("UPDATE event_outbox SET published_at = CURRENT_TIMESTAMP WHERE id = ?", row.id());
                    return true;
                });
                if (!Boolean.TRUE.equals(processed)) break;
            } catch (Exception e) {
                log.warn("Outbox publication failed; row remains pending", e);
                break;
            }
        }
    }

    @Scheduled(fixedDelayString = "${relayhub.workers.poll-ms:500}")
    public void deliverDue() {
        for (int i = 0; i < 25; i++) {
            try {
                Boolean processed = transactions.execute(tx -> {
                    // Lock the event first everywhere, including Kafka materialization and replay.
                    List<String> eventIds = jdbc.queryForList("""
                            SELECT e.id FROM events e WHERE EXISTS (
                                SELECT 1 FROM webhook_deliveries d WHERE d.event_id = e.id
                                AND (d.status = 'PENDING' OR (d.status = 'RETRYING' AND d.next_retry_at <= CURRENT_TIMESTAMP)))
                            ORDER BY e.created_at LIMIT 1 FOR UPDATE OF e SKIP LOCKED
                            """, String.class);
                    if (eventIds.isEmpty()) return false;
                    List<String> deliveries = jdbc.queryForList("""
                            SELECT id FROM webhook_deliveries WHERE event_id = ?
                            AND (status = 'PENDING' OR (status = 'RETRYING' AND next_retry_at <= CURRENT_TIMESTAMP))
                            ORDER BY created_at LIMIT 1
                            """, String.class, eventIds.getFirst());
                    dispatcher.retrySingleDelivery(deliveries.getFirst());
                    return true;
                });
                if (!Boolean.TRUE.equals(processed)) break;
            } catch (Exception e) {
                log.warn("Delivery transaction failed; durable work remains pending", e);
                break;
            }
        }
    }

    private record OutboxRow(long id, String eventId, String kind, String reason) {}
}
