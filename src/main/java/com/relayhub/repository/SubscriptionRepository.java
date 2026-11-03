package com.relayhub.repository;

import com.relayhub.model.Subscription;
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
public class SubscriptionRepository {

    private final JdbcTemplate jdbcTemplate;

    public SubscriptionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<Subscription> ROW_MAPPER = (rs, rowNum) -> new Subscription(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("target_url"),
            rs.getString("secret"),
            rs.getString("event_types"),
            rs.getBoolean("is_active"),
            rs.getInt("max_retries"),
            rs.getLong("initial_interval_ms"),
            rs.getLong("max_interval_ms"),
            rs.getDouble("backoff_multiplier"),
            rs.getInt("timeout_ms"),
            toInstant(rs.getTimestamp("created_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : Instant.now();
    }

    public Optional<Subscription> findById(String id) {
        try {
            Subscription sub = jdbcTemplate.queryForObject(
                    "SELECT id, tenant_id, target_url, secret, event_types, is_active, max_retries, initial_interval_ms, max_interval_ms, backoff_multiplier, timeout_ms, created_at FROM subscriptions WHERE id = ?",
                    ROW_MAPPER,
                    id
            );
            return Optional.ofNullable(sub);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<Subscription> findByTenantId(String tenantId) {
        return jdbcTemplate.query(
                "SELECT id, tenant_id, target_url, secret, event_types, is_active, max_retries, initial_interval_ms, max_interval_ms, backoff_multiplier, timeout_ms, created_at FROM subscriptions WHERE tenant_id = ? ORDER BY created_at DESC",
                ROW_MAPPER,
                tenantId
        );
    }

    public List<Subscription> findActiveByTenantId(String tenantId) {
        return jdbcTemplate.query(
                "SELECT id, tenant_id, target_url, secret, event_types, is_active, max_retries, initial_interval_ms, max_interval_ms, backoff_multiplier, timeout_ms, created_at FROM subscriptions WHERE tenant_id = ? AND is_active = TRUE",
                ROW_MAPPER,
                tenantId
        );
    }

    public Subscription save(Subscription subscription) {
        jdbcTemplate.update(
                """
                INSERT INTO subscriptions (id, tenant_id, target_url, secret, event_types, is_active, max_retries, initial_interval_ms, max_interval_ms, backoff_multiplier, timeout_ms, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    target_url = EXCLUDED.target_url,
                    secret = EXCLUDED.secret,
                    event_types = EXCLUDED.event_types,
                    is_active = EXCLUDED.is_active,
                    max_retries = EXCLUDED.max_retries,
                    initial_interval_ms = EXCLUDED.initial_interval_ms,
                    max_interval_ms = EXCLUDED.max_interval_ms,
                    backoff_multiplier = EXCLUDED.backoff_multiplier,
                    timeout_ms = EXCLUDED.timeout_ms
                """,
                subscription.id(),
                subscription.tenantId(),
                subscription.targetUrl(),
                subscription.secret(),
                subscription.eventTypes(),
                subscription.isActive(),
                subscription.maxRetries(),
                subscription.initialIntervalMs(),
                subscription.maxIntervalMs(),
                subscription.backoffMultiplier(),
                subscription.timeoutMs(),
                Timestamp.from(subscription.createdAt() != null ? subscription.createdAt() : Instant.now())
        );
        return subscription;
    }

    public boolean delete(String id, String tenantId) {
        int rows = jdbcTemplate.update(
                "DELETE FROM subscriptions WHERE id = ? AND tenant_id = ?",
                id, tenantId
        );
        return rows > 0;
    }
}

