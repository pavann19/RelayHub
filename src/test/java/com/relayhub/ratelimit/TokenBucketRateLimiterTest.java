package com.relayhub.ratelimit;

import com.relayhub.dto.RateLimitResult;
import com.relayhub.model.Tenant;
import com.relayhub.model.TenantTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBucketRateLimiterTest {

    private InMemoryTokenBucketRateLimiter rateLimiter;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        rateLimiter = new InMemoryTokenBucketRateLimiter();
        // Rate: 10 req/s, Burst: 5, Monthly Quota: 100
        tenant = new Tenant("tenant_test", "Test Tenant", TenantTier.FREE, 10, 5, 100L, null);
    }

    @Test
    @DisplayName("Should allow requests within burst capacity")
    void shouldAllowWithinBurstCapacity() {
        for (int i = 0; i < 5; i++) {
            RateLimitResult result = rateLimiter.tryConsume(tenant, 1);
            assertTrue(result.allowed(), "Request " + (i + 1) + " should be allowed");
            assertEquals(5, result.limit());
            assertEquals(4 - i, result.remaining());
        }
    }

    @Test
    @DisplayName("Should reject requests once burst capacity is exhausted")
    void shouldRejectWhenExhausted() {
        // Consume all 5 burst tokens
        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.tryConsume(tenant, 1).allowed());
        }

        // 6th request must be rejected
        RateLimitResult rejected = rateLimiter.tryConsume(tenant, 1);
        assertFalse(rejected.allowed(), "6th request must be rejected");
        assertEquals(0, rejected.remaining());
        assertTrue(rejected.retryAfterSeconds() >= 1, "retryAfterSeconds should be at least 1");
    }

    @Test
    @DisplayName("Should replenish tokens over time")
    void shouldReplenishTokensOverTime() throws InterruptedException {
        // Exhaust tokens
        for (int i = 0; i < 5; i++) {
            rateLimiter.tryConsume(tenant, 1);
        }
        assertFalse(rateLimiter.tryConsume(tenant, 1).allowed());

        // Wait 300ms (at 10 tokens/sec, ~3 tokens will refill)
        Thread.sleep(300);

        RateLimitResult replenished = rateLimiter.tryConsume(tenant, 1);
        assertTrue(replenished.allowed(), "Should be allowed after token replenishment");
    }

    @Test
    @DisplayName("Should enforce monthly quota limits")
    void shouldEnforceMonthlyQuota() {
        // Quota is 5 tokens
        Tenant quotaTenant = new Tenant("tenant_quota", "Quota Tenant", TenantTier.FREE, 100, 100, 5L, null);

        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.tryConsume(quotaTenant, 1).allowed());
        }

        // 6th request exceeds monthly quota
        RateLimitResult exceeded = rateLimiter.tryConsume(quotaTenant, 1);
        assertFalse(exceeded.allowed(), "Request exceeding monthly quota must be rejected");
        assertEquals(3600L, exceeded.retryAfterSeconds());
    }

    @Test
    @DisplayName("Should isolate rate limit buckets between different tenants")
    void shouldIsolateBetweenTenants() {
        Tenant tenantA = new Tenant("tenant_a", "Tenant A", TenantTier.FREE, 10, 2, 1000L, null);
        Tenant tenantB = new Tenant("tenant_b", "Tenant B", TenantTier.FREE, 10, 2, 1000L, null);

        // Exhaust Tenant A
        assertTrue(rateLimiter.tryConsume(tenantA, 1).allowed());
        assertTrue(rateLimiter.tryConsume(tenantA, 1).allowed());
        assertFalse(rateLimiter.tryConsume(tenantA, 1).allowed());

        // Tenant B should still have full capacity
        assertTrue(rateLimiter.tryConsume(tenantB, 1).allowed());
        assertTrue(rateLimiter.tryConsume(tenantB, 1).allowed());
    }
}

