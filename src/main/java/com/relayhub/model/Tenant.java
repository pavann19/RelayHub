package com.relayhub.model;

import java.time.Instant;

public record Tenant(
        String id,
        String name,
        TenantTier tier,
        int rateLimitPerSecond,
        int burstCapacity,
        long monthlyQuota,
        Instant createdAt
) {
    public static Tenant of(String id, String name, TenantTier tier) {
        return new Tenant(
                id,
                name,
                tier,
                tier.getDefaultRateLimit(),
                tier.getDefaultBurst(),
                tier.getDefaultMonthlyQuota(),
                Instant.now()
        );
    }
}

