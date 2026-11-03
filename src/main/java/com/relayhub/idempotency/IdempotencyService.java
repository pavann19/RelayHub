package com.relayhub.idempotency;

import com.relayhub.model.IdempotencyRecord;
import com.relayhub.model.IdempotencyStatus;
import com.relayhub.repository.IdempotencyRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class IdempotencyService {

    private static final long LOCK_TTL_SECONDS = 120L;
    private final IdempotencyRepository repository;

    public IdempotencyService(IdempotencyRepository repository) {
        this.repository = repository;
    }

    public IdempotencyOutcome checkOrAcquire(String tenantId, String idempotencyKey, String payload) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return IdempotencyOutcome.acquired();
        }

        String requestHash = RequestHasher.sha256(payload);
        Optional<IdempotencyRecord> existingOpt = repository.find(tenantId, idempotencyKey);

        if (existingOpt.isPresent()) {
            IdempotencyRecord record = existingOpt.get();

            // Validate that the request payload hash matches
            if (!record.requestHash().equals(requestHash)) {
                return IdempotencyOutcome.mismatch();
            }

            if (record.status() == IdempotencyStatus.COMPLETED) {
                return IdempotencyOutcome.cached(
                        record.responseStatus() != null ? record.responseStatus() : 200,
                        record.responseBody()
                );
            } else {
                return IdempotencyOutcome.inProgress();
            }
        }

        // Try to atomically acquire
        boolean acquired = repository.tryAcquire(tenantId, idempotencyKey, requestHash, LOCK_TTL_SECONDS);
        if (acquired) {
            return IdempotencyOutcome.acquired();
        }

        // In case of race condition, re-read
        return repository.find(tenantId, idempotencyKey)
                .map(r -> {
                    if (!r.requestHash().equals(requestHash)) {
                        return IdempotencyOutcome.mismatch();
                    }
                    if (r.status() == IdempotencyStatus.COMPLETED) {
                        return IdempotencyOutcome.cached(
                                r.responseStatus() != null ? r.responseStatus() : 200,
                                r.responseBody()
                        );
                    }
                    return IdempotencyOutcome.inProgress();
                })
                .orElse(IdempotencyOutcome.inProgress());
    }

    public void complete(String tenantId, String idempotencyKey, int responseStatus, String responseBody) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            repository.complete(tenantId, idempotencyKey, responseStatus, responseBody);
        }
    }

    public void release(String tenantId, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            repository.delete(tenantId, idempotencyKey);
        }
    }
}

