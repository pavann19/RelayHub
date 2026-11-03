package com.relayhub.model;

public enum TenantTier {
    FREE(10, 20, 10_000L),
    PRO(100, 200, 500_000L),
    ENTERPRISE(1000, 2000, 10_000_000L);

    private final int defaultRateLimit;
    private final int defaultBurst;
    private final long defaultMonthlyQuota;

    TenantTier(int defaultRateLimit, int defaultBurst, long defaultMonthlyQuota) {
        this.defaultRateLimit = defaultRateLimit;
        this.defaultBurst = defaultBurst;
        this.defaultMonthlyQuota = defaultMonthlyQuota;
    }

    public int getDefaultRateLimit() {
        return defaultRateLimit;
    }

    public int getDefaultBurst() {
        return defaultBurst;
    }

    public long getDefaultMonthlyQuota() {
        return defaultMonthlyQuota;
    }
}

