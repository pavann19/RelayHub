package com.relayhub.dto;

public record RateLimitResult(
        boolean allowed,
        int limit,
        int remaining,
        long resetSeconds,
        long retryAfterSeconds
) {
    public static RateLimitResult ok(int limit, int remaining, long resetSeconds) {
        return new RateLimitResult(true, limit, remaining, resetSeconds, 0L);
    }

    public static RateLimitResult rejected(int limit, long resetSeconds, long retryAfterSeconds) {
        return new RateLimitResult(false, limit, 0, resetSeconds, retryAfterSeconds);
    }
}

