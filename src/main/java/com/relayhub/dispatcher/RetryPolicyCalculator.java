package com.relayhub.dispatcher;

import com.relayhub.model.Subscription;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class RetryPolicyCalculator {

    public Instant calculateNextRetry(Subscription subscription, int currentAttempt, String retryAfterHeader) {
        long baseInterval = subscription.initialIntervalMs();
        long maxInterval = subscription.maxIntervalMs();
        double multiplier = subscription.backoffMultiplier();

        // 1. Calculate exponential backoff
        double unjitteredMs = baseInterval * Math.pow(multiplier, Math.max(0, currentAttempt - 1));
        long boundedMs = (long) Math.min(maxInterval, unjitteredMs);

        // 2. Full Jitter: Uniform random between [0, boundedMs]
        long jitteredMs = ThreadLocalRandom.current().nextLong(Math.max(100, boundedMs / 2), boundedMs + 1);

        // 3. Inspect downstream Retry-After header if present
        long retryAfterMs = parseRetryAfter(retryAfterHeader);
        long effectiveDelayMs = Math.max(jitteredMs, retryAfterMs);

        return Instant.now().plusMillis(effectiveDelayMs);
    }

    public long parseRetryAfter(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return 0L;
        }
        headerValue = headerValue.trim();

        // Check if delta-seconds format
        try {
            long seconds = Long.parseLong(headerValue);
            return Math.max(0, seconds * 1000L);
        } catch (NumberFormatException ignored) {
            // Check if HTTP-date format (RFC 7231 / RFC 1123)
            try {
                Instant target = DateTimeFormatter.RFC_1123_DATE_TIME.parse(headerValue, Instant::from);
                long diff = Duration.between(Instant.now(), target).toMillis();
                return Math.max(0, diff);
            } catch (DateTimeParseException ignoredDate) {
                return 0L;
            }
        }
    }
}

