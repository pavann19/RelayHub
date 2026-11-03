package com.relayhub.dispatcher;

import com.relayhub.model.Subscription;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryPolicyCalculatorTest {

    private RetryPolicyCalculator calculator;
    private Subscription subscription;

    @BeforeEach
    void setUp() {
        calculator = new RetryPolicyCalculator();
        // Initial: 1000ms, Max: 10000ms, Multiplier: 2.0
        subscription = new Subscription(
                "sub_1", "tenant_1", "https://example.com/webhook", "secret",
                "*", true, 5, 1000L, 10000L, 2.0, 5000, Instant.now()
        );
    }

    @Test
    @DisplayName("Should calculate exponential backoff within bounds")
    void shouldCalculateExponentialBackoff() {
        Instant now = Instant.now();

        // Attempt 1: base = 1000ms, with jitter delay is between [500ms, 1000ms]
        Instant retry1 = calculator.calculateNextRetry(subscription, 1, null);
        long delay1 = Duration.between(now, retry1).toMillis();
        assertTrue(delay1 >= 450 && delay1 <= 1500, "Attempt 1 delay should be ~1000ms (got: " + delay1 + ")");

        // Attempt 2: base = 2000ms
        Instant retry2 = calculator.calculateNextRetry(subscription, 2, null);
        long delay2 = Duration.between(now, retry2).toMillis();
        assertTrue(delay2 >= 900 && delay2 <= 2500, "Attempt 2 delay should be ~2000ms (got: " + delay2 + ")");

        // Attempt 3: base = 4000ms
        Instant retry3 = calculator.calculateNextRetry(subscription, 3, null);
        long delay3 = Duration.between(now, retry3).toMillis();
        assertTrue(delay3 >= 1900 && delay3 <= 4500, "Attempt 3 delay should be ~4000ms (got: " + delay3 + ")");

        // Attempt 10: capped at maxInterval = 10000ms
        Instant retry10 = calculator.calculateNextRetry(subscription, 10, null);
        long delay10 = Duration.between(now, retry10).toMillis();
        assertTrue(delay10 <= 10500, "Should not exceed maxIntervalMs (got: " + delay10 + ")");
    }

    @Test
    @DisplayName("Should parse downstream Retry-After delta seconds header")
    void shouldParseRetryAfterSeconds() {
        long delayMs = calculator.parseRetryAfter("120");
        assertEquals(120_000L, delayMs, "120 seconds should parse to 120,000 ms");
    }

    @Test
    @DisplayName("Should prioritize larger downstream Retry-After over default jittered backoff")
    void shouldRespectDownstreamRetryAfter() {
        Instant now = Instant.now();
        // Downstream specifies 30 seconds
        Instant nextRetry = calculator.calculateNextRetry(subscription, 1, "30");
        long diffSeconds = Duration.between(now, nextRetry).toSeconds();

        assertTrue(diffSeconds >= 29 && diffSeconds <= 32, "Should respect Retry-After: 30s");
    }

    @Test
    @DisplayName("Should handle missing or invalid Retry-After header gracefully")
    void shouldHandleInvalidRetryAfter() {
        assertEquals(0L, calculator.parseRetryAfter(null));
        assertEquals(0L, calculator.parseRetryAfter(""));
        assertEquals(0L, calculator.parseRetryAfter("not-a-valid-date-or-number"));
    }
}

