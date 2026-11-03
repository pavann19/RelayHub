package com.relayhub.idempotency;

public record IdempotencyOutcome(
        Type type,
        Integer cachedStatus,
        String cachedBody,
        String message
) {
    public enum Type {
        ACQUIRED,
        CACHED,
        CONFLICT_IN_PROGRESS,
        MISMATCHED_PAYLOAD
    }

    public static IdempotencyOutcome acquired() {
        return new IdempotencyOutcome(Type.ACQUIRED, null, null, null);
    }

    public static IdempotencyOutcome cached(int status, String body) {
        return new IdempotencyOutcome(Type.CACHED, status, body, "Idempotent replay of previously committed request");
    }

    public static IdempotencyOutcome inProgress() {
        return new IdempotencyOutcome(Type.CONFLICT_IN_PROGRESS, 409, null, "Concurrent request with same Idempotency-Key is currently in progress");
    }

    public static IdempotencyOutcome mismatch() {
        return new IdempotencyOutcome(Type.MISMATCHED_PAYLOAD, 422, null, "Idempotency key reuse detected with mismatched request payload");
    }
}

