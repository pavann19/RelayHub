package com.relayhub.repository;

import com.relayhub.model.IdempotencyRecord;
import com.relayhub.model.IdempotencyStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbcTemplate;

    public IdempotencyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<IdempotencyRecord> ROW_MAPPER = (rs, rowNum) -> new IdempotencyRecord(
            rs.getString("tenant_id"),
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            IdempotencyStatus.valueOf(rs.getString("status")),
            rs.getObject("response_status") != null ? rs.getInt("response_status") : null,
            rs.getString("response_body"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("expires_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    public Optional<IdempotencyRecord> find(String tenantId, String idempotencyKey) {
        try {
            IdempotencyRecord record = jdbcTemplate.queryForObject(
                    "SELECT tenant_id, idempotency_key, request_hash, status, response_status, response_body, created_at, expires_at FROM idempotency_records WHERE tenant_id = ? AND idempotency_key = ? AND expires_at > CURRENT_TIMESTAMP",
                    ROW_MAPPER,
                    tenantId,
                    idempotencyKey
            );
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public boolean tryAcquire(String tenantId, String idempotencyKey, String requestHash, long ttlSeconds) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        try {
            int rows = jdbcTemplate.update(
                    """
                    INSERT INTO idempotency_records (tenant_id, idempotency_key, request_hash, status, created_at, expires_at)
                    VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?)
                    ON CONFLICT (tenant_id, idempotency_key) DO UPDATE SET
                        request_hash = EXCLUDED.request_hash, status = 'IN_PROGRESS',
                        created_at = EXCLUDED.created_at, expires_at = EXCLUDED.expires_at,
                        response_status = NULL, response_body = NULL
                    WHERE idempotency_records.expires_at <= CURRENT_TIMESTAMP
                    """,
                    tenantId,
                    idempotencyKey,
                    requestHash,
                    Timestamp.from(now),
                    Timestamp.from(expiresAt)
            );
            return rows > 0;
        } catch (DuplicateKeyException e) {
            // Already acquired or completed
            return false;
        }
    }

    public void complete(String tenantId, String idempotencyKey, int responseStatus, String responseBody) {
        jdbcTemplate.update(
                """
                UPDATE idempotency_records
                SET status = 'COMPLETED', response_status = ?, response_body = ?, expires_at = CURRENT_TIMESTAMP + INTERVAL '24 hours'
                WHERE tenant_id = ? AND idempotency_key = ?
                """,
                responseStatus,
                responseBody,
                tenantId,
                idempotencyKey
        );
    }

    public void delete(String tenantId, String idempotencyKey) {
        jdbcTemplate.update(
                "DELETE FROM idempotency_records WHERE tenant_id = ? AND idempotency_key = ?",
                tenantId,
                idempotencyKey
        );
    }

    public int cleanupExpired() {
        return jdbcTemplate.update(
                "DELETE FROM idempotency_records WHERE expires_at < ?",
                Timestamp.from(Instant.now())
        );
    }
}

