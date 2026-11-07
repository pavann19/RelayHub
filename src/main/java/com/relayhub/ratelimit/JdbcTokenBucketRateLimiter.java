package com.relayhub.ratelimit;

import com.relayhub.dto.RateLimitResult;
import com.relayhub.model.Tenant;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared, persistent bucket and UTC monthly quota, updated under one row lock. */
@Component
public class JdbcTokenBucketRateLimiter implements RateLimiter {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcTokenBucketRateLimiter(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public RateLimitResult tryConsume(Tenant tenant, int requested) {
        if (tenant == null || requested < 1) throw new IllegalArgumentException("Tenant and positive token count required");
        return transactions.execute(tx -> {
            jdbc.update("""
                    INSERT INTO tenant_rate_limits(tenant_id, tokens, quota_month)
                    VALUES (?, ?, to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM'))
                    ON CONFLICT (tenant_id) DO NOTHING
                    """, tenant.id(), tenant.burstCapacity());
            Bucket bucket = jdbc.queryForObject("""
                    SELECT tokens, updated_at, quota_month, usage FROM tenant_rate_limits
                    WHERE tenant_id = ? FOR UPDATE
                    """, (rs, n) -> new Bucket(rs.getDouble(1), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getLong(4)), tenant.id());
            Instant now = jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
            var utc = now.atZone(ZoneOffset.UTC);
            String month = java.time.YearMonth.from(utc).toString();
            long usage = month.equals(bucket.month()) ? bucket.usage() : 0;
            double available = Math.min(tenant.burstCapacity(), bucket.tokens()
                    + Math.max(0, now.toEpochMilli() - bucket.updated().toEpochMilli()) * tenant.rateLimitPerSecond() / 1000.0);
            RateLimitResult result;
            if (requested > tenant.monthlyQuota() - usage) {
                long reset = Math.max(1, java.time.Duration.between(now,
                        utc.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant()).toSeconds());
                result = RateLimitResult.rejected(tenant.burstCapacity(), reset, reset);
            } else if (available < requested) {
                long retry = Math.max(1, (long) Math.ceil((requested - available) / Math.max(1, tenant.rateLimitPerSecond())));
                result = RateLimitResult.rejected(tenant.burstCapacity(), retry, retry);
            } else {
                available -= requested;
                usage += requested;
                result = RateLimitResult.ok(tenant.burstCapacity(), (int) available, 1);
            }
            jdbc.update("UPDATE tenant_rate_limits SET tokens = ?, updated_at = ?, quota_month = ?, usage = ? WHERE tenant_id = ?",
                    available, java.sql.Timestamp.from(now), month, usage, tenant.id());
            return result;
        });
    }

    private record Bucket(double tokens, Instant updated, String month, long usage) {}
}
