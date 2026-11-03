package com.relayhub.repository;

import com.relayhub.model.Tenant;
import com.relayhub.model.TenantTier;
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
public class TenantRepository {

    private final JdbcTemplate jdbcTemplate;

    public TenantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<Tenant> ROW_MAPPER = (rs, rowNum) -> new Tenant(
            rs.getString("id"),
            rs.getString("name"),
            TenantTier.valueOf(rs.getString("tier")),
            rs.getInt("rate_limit_per_second"),
            rs.getInt("burst_capacity"),
            rs.getLong("monthly_quota"),
            toInstant(rs.getTimestamp("created_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : Instant.now();
    }

    public Optional<Tenant> findById(String id) {
        try {
            Tenant tenant = jdbcTemplate.queryForObject(
                    "SELECT id, name, tier, rate_limit_per_second, burst_capacity, monthly_quota, created_at FROM tenants WHERE id = ?",
                    ROW_MAPPER,
                    id
            );
            return Optional.ofNullable(tenant);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<Tenant> findAll() {
        return jdbcTemplate.query(
                "SELECT id, name, tier, rate_limit_per_second, burst_capacity, monthly_quota, created_at FROM tenants ORDER BY created_at DESC",
                ROW_MAPPER
        );
    }

    public Tenant save(Tenant tenant) {
        jdbcTemplate.update(
                """
                INSERT INTO tenants (id, name, tier, rate_limit_per_second, burst_capacity, monthly_quota, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    tier = EXCLUDED.tier,
                    rate_limit_per_second = EXCLUDED.rate_limit_per_second,
                    burst_capacity = EXCLUDED.burst_capacity,
                    monthly_quota = EXCLUDED.monthly_quota
                """,
                tenant.id(),
                tenant.name(),
                tenant.tier().name(),
                tenant.rateLimitPerSecond(),
                tenant.burstCapacity(),
                tenant.monthlyQuota(),
                Timestamp.from(tenant.createdAt() != null ? tenant.createdAt() : Instant.now())
        );
        return tenant;
    }
}

