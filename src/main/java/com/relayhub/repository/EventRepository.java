package com.relayhub.repository;

import com.relayhub.model.Event;
import com.relayhub.model.EventStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {

    private final JdbcTemplate jdbcTemplate;

    public EventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<Event> ROW_MAPPER = (rs, rowNum) -> new Event(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("event_type"),
            rs.getString("idempotency_key"),
            rs.getString("payload"),
            EventStatus.valueOf(rs.getString("status")),
            toInstant(rs.getTimestamp("created_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : Instant.now();
    }

    public Optional<Event> findById(String id) {
        try {
            Event event = jdbcTemplate.queryForObject(
                    "SELECT id, tenant_id, event_type, idempotency_key, payload, status, created_at FROM events WHERE id = ?",
                    ROW_MAPPER,
                    id
            );
            return Optional.ofNullable(event);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<Event> findByTenantId(String tenantId, int limit) {
        return jdbcTemplate.query(
                "SELECT id, tenant_id, event_type, idempotency_key, payload, status, created_at FROM events WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?",
                ROW_MAPPER,
                tenantId,
                limit
        );
    }

    public Event save(Event event) {
        jdbcTemplate.update(
                """
                INSERT INTO events (id, tenant_id, event_type, idempotency_key, payload, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    status = EXCLUDED.status
                """,
                event.id(),
                event.tenantId(),
                event.eventType(),
                event.idempotencyKey(),
                event.payload(),
                event.status().name(),
                Timestamp.from(event.createdAt() != null ? event.createdAt() : Instant.now())
        );
        return event;
    }

    public void updateStatus(String id, EventStatus status) {
        jdbcTemplate.update(
                "UPDATE events SET status = ? WHERE id = ?",
                status.name(),
                id
        );
    }
}

