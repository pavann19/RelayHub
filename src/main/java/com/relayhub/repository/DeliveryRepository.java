package com.relayhub.repository;

import com.relayhub.model.DeliveryStatus;
import com.relayhub.model.WebhookDelivery;
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
public class DeliveryRepository {

    private final JdbcTemplate jdbcTemplate;

    public DeliveryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<WebhookDelivery> ROW_MAPPER = (rs, rowNum) -> new WebhookDelivery(
            rs.getString("id"),
            rs.getString("event_id"),
            rs.getString("subscription_id"),
            rs.getString("tenant_id"),
            rs.getInt("attempt_number"),
            DeliveryStatus.valueOf(rs.getString("status")),
            rs.getObject("http_status") != null ? rs.getInt("http_status") : null,
            rs.getString("request_headers"),
            rs.getString("response_headers"),
            rs.getString("response_body"),
            rs.getString("error_message"),
            rs.getLong("latency_ms"),
            toInstant(rs.getTimestamp("next_retry_at")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("completed_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    public Optional<WebhookDelivery> findById(String id) {
        try {
            WebhookDelivery del = jdbcTemplate.queryForObject(
                    "SELECT id, event_id, subscription_id, tenant_id, attempt_number, status, http_status, request_headers, response_headers, response_body, error_message, latency_ms, next_retry_at, created_at, completed_at FROM webhook_deliveries WHERE id = ?",
                    ROW_MAPPER,
                    id
            );
            return Optional.ofNullable(del);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<WebhookDelivery> findByEventId(String eventId) {
        return jdbcTemplate.query(
                "SELECT id, event_id, subscription_id, tenant_id, attempt_number, status, http_status, request_headers, response_headers, response_body, error_message, latency_ms, next_retry_at, created_at, completed_at FROM webhook_deliveries WHERE event_id = ? ORDER BY attempt_number ASC",
                ROW_MAPPER,
                eventId
        );
    }

    public List<WebhookDelivery> findByTenantId(String tenantId, int limit) {
        return jdbcTemplate.query(
                "SELECT id, event_id, subscription_id, tenant_id, attempt_number, status, http_status, request_headers, response_headers, response_body, error_message, latency_ms, next_retry_at, created_at, completed_at FROM webhook_deliveries WHERE tenant_id = ? ORDER BY created_at DESC LIMIT ?",
                ROW_MAPPER,
                tenantId,
                limit
        );
    }

    public List<WebhookDelivery> findDeadLettersByTenantId(String tenantId) {
        return jdbcTemplate.query(
                "SELECT id, event_id, subscription_id, tenant_id, attempt_number, status, http_status, request_headers, response_headers, response_body, error_message, latency_ms, next_retry_at, created_at, completed_at FROM webhook_deliveries WHERE tenant_id = ? AND status = 'DEAD_LETTERED' ORDER BY created_at DESC",
                ROW_MAPPER,
                tenantId
        );
    }

    public List<String> findAttemptSnapshots(String deliveryId) {
        return jdbcTemplate.queryForList("SELECT snapshot::text FROM webhook_delivery_attempts WHERE delivery_id = ? ORDER BY attempt_number",
                String.class, deliveryId);
    }

    @org.springframework.transaction.annotation.Transactional
    public WebhookDelivery save(WebhookDelivery delivery) {
        jdbcTemplate.update(
                """
                INSERT INTO webhook_deliveries (id, event_id, subscription_id, tenant_id, attempt_number, status, http_status, request_headers, response_headers, response_body, error_message, latency_ms, next_retry_at, created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    attempt_number = EXCLUDED.attempt_number,
                    status = EXCLUDED.status,
                    http_status = EXCLUDED.http_status,
                    request_headers = EXCLUDED.request_headers,
                    response_headers = EXCLUDED.response_headers,
                    response_body = EXCLUDED.response_body,
                    error_message = EXCLUDED.error_message,
                    latency_ms = EXCLUDED.latency_ms,
                    next_retry_at = EXCLUDED.next_retry_at,
                    completed_at = EXCLUDED.completed_at
                """,
                delivery.id(),
                delivery.eventId(),
                delivery.subscriptionId(),
                delivery.tenantId(),
                delivery.attemptNumber(),
                delivery.status().name(),
                delivery.httpStatus(),
                delivery.requestHeaders(),
                delivery.responseHeaders(),
                delivery.responseBody(),
                delivery.errorMessage(),
                delivery.latencyMs(),
                delivery.nextRetryAt() != null ? Timestamp.from(delivery.nextRetryAt()) : null,
                Timestamp.from(delivery.createdAt() != null ? delivery.createdAt() : Instant.now()),
                delivery.completedAt() != null ? Timestamp.from(delivery.completedAt()) : null
        );
        jdbcTemplate.update("""
                INSERT INTO webhook_delivery_attempts (delivery_id, attempt_number, snapshot)
                SELECT id, attempt_number, to_jsonb(d) FROM webhook_deliveries d WHERE id = ? AND status <> 'PENDING'
                ON CONFLICT (delivery_id, attempt_number) DO NOTHING
                """, delivery.id());
        return delivery;
    }
}

