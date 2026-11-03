package com.relayhub.ratelimit;

import com.relayhub.dto.RateLimitResult;
import com.relayhub.model.Tenant;

public interface RateLimiter {

    /**
     * Attempts to consume tokens for the given tenant.
     *
     * @param tenant The authenticated tenant
     * @param tokens Number of tokens to consume (typically 1 per request)
     * @return RateLimitResult indicating success/failure and header values
     */
    RateLimitResult tryConsume(Tenant tenant, int tokens);
}

