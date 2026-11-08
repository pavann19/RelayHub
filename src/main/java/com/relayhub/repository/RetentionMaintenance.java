package com.relayhub.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded, opt-in retention. Never purges unfinished/quarantined/dead-letter work. */
@Component
public class RetentionMaintenance {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    private final int days;
    public RetentionMaintenance(JdbcTemplate jdbc, TransactionTemplate transactions,
            @Value("${relayhub.retention.enabled:false}") boolean enabled,
            @Value("${relayhub.retention.success-days:90}") int days) {
        if (days < 1) throw new IllegalArgumentException("Retention must be at least one day");
        this.jdbc=jdbc; this.transactions=transactions; this.enabled=enabled; this.days=days;
    }
    @Scheduled(fixedDelay = 3600000, initialDelay = 60000)
    public void clean() {
        transactions.executeWithoutResult(tx -> {
            jdbc.update("""
                    DELETE FROM idempotency_records WHERE (tenant_id,idempotency_key) IN
                    (SELECT tenant_id,idempotency_key FROM idempotency_records WHERE expires_at<CURRENT_TIMESTAMP LIMIT 500)
                    """);
            if (!enabled) return;
            // Delete only published outbox metadata, never pending or quarantined work.
            jdbc.update("""
                    DELETE FROM event_outbox WHERE id IN (SELECT id FROM event_outbox
                    WHERE published_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day') LIMIT 500)
                    """, days);
            jdbc.update("""
                    DELETE FROM events WHERE id IN (SELECT e.id FROM events e
                    WHERE e.created_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')
                    AND e.status='DELIVERED'
                    AND NOT EXISTS(SELECT 1 FROM event_outbox o WHERE o.event_id=e.id)
                    AND NOT EXISTS(SELECT 1 FROM webhook_deliveries d WHERE d.event_id=e.id AND d.status<>'SUCCESS')
                    AND NOT EXISTS(SELECT 1 FROM idempotency_records i WHERE i.tenant_id=e.tenant_id
                        AND i.idempotency_key=e.idempotency_key AND i.expires_at>CURRENT_TIMESTAMP)
                    LIMIT 100)
                    """, days);
        });
    }
}
