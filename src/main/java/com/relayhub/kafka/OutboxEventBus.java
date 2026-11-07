package com.relayhub.kafka;

import com.relayhub.model.Event;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Joins ingestion/delivery transactions; transport I/O happens after commit. */
@Component
@Primary
public class OutboxEventBus implements EventBus {
    private final JdbcTemplate jdbc;

    public OutboxEventBus(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public void publish(Event event) { enqueue(event, "EVENT", null); }

    @Override
    @Transactional
    public void publishDlq(Event event, String reason) { enqueue(event, "DLQ", reason); }

    private void enqueue(Event event, String kind, String reason) {
        jdbc.update("INSERT INTO event_outbox(event_id, kind, reason) VALUES (?, ?, ?)",
                event.id(), kind, reason);
    }
}
