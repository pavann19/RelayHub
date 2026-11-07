package com.relayhub.ratelimit;

import com.relayhub.dto.RateLimitResult;
import com.relayhub.model.Tenant;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

public class InMemoryTokenBucketRateLimiter implements RateLimiter {

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> monthlyUsage = new ConcurrentHashMap<>();

    @Override
    public RateLimitResult tryConsume(Tenant tenant, int tokens) {
        if (tenant == null) {
            return RateLimitResult.ok(100, 100, 1L);
        }

        // 1. Quota Check
        AtomicLong usage = monthlyUsage.computeIfAbsent(tenant.id(), k -> new AtomicLong(0));
        if (usage.get() >= tenant.monthlyQuota()) {
            return RateLimitResult.rejected(tenant.burstCapacity(), 3600L, 3600L);
        }

        // 2. Token Bucket Rate Limit Check
        TokenBucket bucket = buckets.computeIfAbsent(
                tenant.id(),
                k -> new TokenBucket(tenant.burstCapacity(), tenant.rateLimitPerSecond())
        );

        // Update parameters if tenant tier changed dynamically
        bucket.updateLimits(tenant.burstCapacity(), tenant.rateLimitPerSecond());

        RateLimitResult result = bucket.tryConsume(tokens);
        if (result.allowed()) {
            usage.addAndGet(tokens);
        }
        return result;
    }

    public void reset(String tenantId) {
        buckets.remove(tenantId);
        monthlyUsage.remove(tenantId);
    }

    public long getMonthlyUsage(String tenantId) {
        AtomicLong usage = monthlyUsage.get(tenantId);
        return usage != null ? usage.get() : 0L;
    }

    private static class TokenBucket {
        private volatile int capacity;
        private volatile int refillRatePerSecond;
        private double availableTokens;
        private long lastRefillTimestampMs;

        public TokenBucket(int capacity, int refillRatePerSecond) {
            this.capacity = capacity;
            this.refillRatePerSecond = refillRatePerSecond;
            this.availableTokens = capacity;
            this.lastRefillTimestampMs = System.currentTimeMillis();
        }

        public synchronized void updateLimits(int newCapacity, int newRefillRate) {
            this.capacity = newCapacity;
            this.refillRatePerSecond = newRefillRate;
            if (this.availableTokens > newCapacity) {
                this.availableTokens = newCapacity;
            }
        }

        public synchronized RateLimitResult tryConsume(int requestedTokens) {
            long now = System.currentTimeMillis();
            long elapsedMs = Math.max(0, now - lastRefillTimestampMs);
            lastRefillTimestampMs = now;

            // Refill tokens based on elapsed time
            double tokensToAdd = (elapsedMs * refillRatePerSecond) / 1000.0;
            availableTokens = Math.min(capacity, availableTokens + tokensToAdd);

            int limit = capacity;
            long resetSeconds = 1L;

            if (availableTokens >= requestedTokens) {
                availableTokens -= requestedTokens;
                int remaining = (int) Math.floor(availableTokens);
                return RateLimitResult.ok(limit, remaining, resetSeconds);
            } else {
                double missingTokens = requestedTokens - availableTokens;
                long retryAfterSeconds = Math.max(1, (long) Math.ceil(missingTokens / refillRatePerSecond));
                return RateLimitResult.rejected(limit, resetSeconds, retryAfterSeconds);
            }
        }
    }
}

