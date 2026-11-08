package com.relayhub.dispatcher;

import com.relayhub.model.*;
import com.relayhub.repository.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Short claim/finalize transactions; HTTP never holds a database lock or connection. */
@Service
public class DeliveryLeaseWorker {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EventRepository events;
    private final DeliveryRepository deliveries;
    private final SubscriptionRepository subscriptions;
    private final WebhookDispatcher dispatcher;

    public DeliveryLeaseWorker(JdbcTemplate jdbc, TransactionTemplate transactions, EventRepository events,
            DeliveryRepository deliveries, SubscriptionRepository subscriptions, WebhookDispatcher dispatcher) {
        this.jdbc = jdbc; this.transactions = transactions; this.events = events;
        this.deliveries = deliveries; this.subscriptions = subscriptions; this.dispatcher = dispatcher;
    }

    public void process(String id) {
        Claim claim = transactions.execute(tx -> {
            WebhookDelivery old = deliveries.findById(id).orElseThrow();
            events.lock(old.eventId());
            old = deliveries.findById(id).orElseThrow();
            Instant now = jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
            boolean pending = old.status() == DeliveryStatus.PENDING;
            if (!pending && !((old.status() == DeliveryStatus.RETRYING || old.status() == DeliveryStatus.IN_FLIGHT)
                    && old.nextRetryAt() != null && !old.nextRetryAt().isAfter(now))) return null;
            Subscription sub = subscriptions.findById(old.subscriptionId()).orElseThrow();
            Event event = events.findById(old.eventId()).orElseThrow();
            if (!sub.isActive()) {
                jdbc.update("UPDATE webhook_delivery_claims SET outcome='CANCELLED',finished_at=CURRENT_TIMESTAMP WHERE delivery_id=? AND outcome='IN_FLIGHT'",id);
                jdbc.update("UPDATE webhook_deliveries SET claim_token=NULL WHERE id=?",id);
                dispatcher.retrySingleDelivery(id); // Cancel without making a request.
                return null;
            }
            if (old.status() == DeliveryStatus.IN_FLIGHT) jdbc.update("""
                    UPDATE webhook_delivery_claims SET outcome = 'UNKNOWN', finished_at = CURRENT_TIMESTAMP
                    WHERE delivery_id = ? AND outcome = 'IN_FLIGHT'
                    """, id);
            int attempt = pending ? 1 : old.attemptNumber() + 1;
            String token = UUID.randomUUID().toString();
            Instant until = now.plusMillis(sub.timeoutMs() + 10000L);
            jdbc.update("""
                    UPDATE webhook_deliveries SET status = 'IN_FLIGHT', claim_token = ?,
                    attempt_number = ?, next_retry_at = ? WHERE id = ?
                    """, token, attempt, java.sql.Timestamp.from(until), id);
            jdbc.update("INSERT INTO webhook_delivery_claims(token,delivery_id,attempt_number,lease_until) VALUES (?,?,?,?)",
                    token, id, attempt, java.sql.Timestamp.from(until));
            return new Claim(event, sub, token, attempt);
        });
        if (claim == null) return;
        WebhookDelivery result = dispatcher.computeAttempt(claim.event(), claim.subscription(), claim.attempt(), id);
        transactions.executeWithoutResult(tx -> {
            events.lock(claim.event().id());
            String current = jdbc.queryForObject("SELECT claim_token FROM webhook_deliveries WHERE id = ?", String.class, id);
            if (!claim.token().equals(current)) return; // Fenced: expired worker cannot overwrite its replacement.
            dispatcher.persistAttempt(claim.event(), result);
            jdbc.update("UPDATE webhook_deliveries SET claim_token = NULL WHERE id = ?", id);
            jdbc.update("UPDATE webhook_delivery_claims SET outcome = ?, finished_at = CURRENT_TIMESTAMP WHERE token = ?",
                    result.status().name(), claim.token());
        });
    }

    private record Claim(Event event, Subscription subscription, String token, int attempt) {}
}
