package com.relayhub.repository;

import com.relayhub.model.ApiKey;
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
public class ApiKeyRepository {

    private final JdbcTemplate jdbcTemplate;

    public ApiKeyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<ApiKey> ROW_MAPPER = (rs, rowNum) -> new ApiKey(
            rs.getString("id"),
            rs.getString("tenant_id"),
            rs.getString("key_prefix"),
            rs.getString("key_hash"),
            rs.getString("name"),
            rs.getString("role"),
            rs.getBoolean("is_active"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("expires_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    public Optional<ApiKey> findByKeyHash(String keyHash) {
        try {
            ApiKey key = jdbcTemplate.queryForObject(
                    "SELECT id, tenant_id, key_prefix, key_hash, name, role, is_active, created_at, expires_at FROM api_keys WHERE key_hash = ?",
                    ROW_MAPPER,
                    keyHash
            );
            return Optional.ofNullable(key);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<ApiKey> findByTenantId(String tenantId) {
        return jdbcTemplate.query(
                "SELECT id, tenant_id, key_prefix, key_hash, name, role, is_active, created_at, expires_at FROM api_keys WHERE tenant_id = ? ORDER BY created_at DESC",
                ROW_MAPPER,
                tenantId
        );
    }

    public Optional<ApiKey> findById(String id) {
        try {
            ApiKey key = jdbcTemplate.queryForObject(
                    "SELECT id, tenant_id, key_prefix, key_hash, name, role, is_active, created_at, expires_at FROM api_keys WHERE id = ?",
                    ROW_MAPPER,
                    id
            );
            return Optional.ofNullable(key);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public ApiKey save(ApiKey apiKey) {
        jdbcTemplate.update(
                """
                INSERT INTO api_keys (id, tenant_id, key_prefix, key_hash, name, role, is_active, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    role = EXCLUDED.role,
                    is_active = EXCLUDED.is_active,
                    expires_at = EXCLUDED.expires_at
                """,
                apiKey.id(),
                apiKey.tenantId(),
                apiKey.keyPrefix(),
                apiKey.keyHash(),
                apiKey.name(),
                apiKey.role(),
                apiKey.isActive(),
                Timestamp.from(apiKey.createdAt() != null ? apiKey.createdAt() : Instant.now()),
                apiKey.expiresAt() != null ? Timestamp.from(apiKey.expiresAt()) : null
        );
        return apiKey;
    }

    public boolean revoke(String id, String tenantId) {
        int rows = jdbcTemplate.update(
                "UPDATE api_keys SET is_active = FALSE WHERE id = ? AND tenant_id = ?",
                id, tenantId
        );
        return rows > 0;
    }
}

