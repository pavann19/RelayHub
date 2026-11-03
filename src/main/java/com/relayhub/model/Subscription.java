package com.relayhub.model;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public record Subscription(
        String id,
        String tenantId,
        String targetUrl,
        String secret,
        String eventTypes,
        boolean isActive,
        int maxRetries,
        long initialIntervalMs,
        long maxIntervalMs,
        double backoffMultiplier,
        int timeoutMs,
        Instant createdAt
) {
    public boolean matchesEventType(String eventType) {
        if (!isActive) {
            return false;
        }
        if ("*".equals(eventTypes.trim())) {
            return true;
        }
        Set<String> types = Arrays.stream(eventTypes.split(","))
                .map(String::trim)
                .collect(Collectors.toSet());
        return types.contains("*") || types.contains(eventType);
    }
}

