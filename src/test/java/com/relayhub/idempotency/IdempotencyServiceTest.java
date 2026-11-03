package com.relayhub.idempotency;

import com.relayhub.model.IdempotencyRecord;
import com.relayhub.model.IdempotencyStatus;
import com.relayhub.repository.IdempotencyRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IdempotencyServiceTest {

    private FakeIdempotencyRepository repository;
    private IdempotencyService idempotencyService;
    private final String tenantId = "tenant_test";
    private final String idempotencyKey = "key_123456";
    private final String payload = "{\"orderId\":\"ord_123\"}";

    @BeforeEach
    void setUp() {
        repository = new FakeIdempotencyRepository();
        idempotencyService = new IdempotencyService(repository);
    }

    @Test
    @DisplayName("Should acquire lock on first request")
    void shouldAcquireOnFirstRequest() {
        IdempotencyOutcome outcome = idempotencyService.checkOrAcquire(tenantId, idempotencyKey, payload);
        assertEquals(IdempotencyOutcome.Type.ACQUIRED, outcome.type());
    }

    @Test
    @DisplayName("Should return cached response on duplicate request with identical payload")
    void shouldReturnCachedOnDuplicate() {
        String requestHash = RequestHasher.sha256(payload);
        repository.records.put(tenantId + ":" + idempotencyKey, new IdempotencyRecord(
                tenantId,
                idempotencyKey,
                requestHash,
                IdempotencyStatus.COMPLETED,
                201,
                "{\"status\":\"CREATED\"}",
                Instant.now(),
                Instant.now().plusSeconds(3600)
        ));

        IdempotencyOutcome outcome = idempotencyService.checkOrAcquire(tenantId, idempotencyKey, payload);
        assertEquals(IdempotencyOutcome.Type.CACHED, outcome.type());
        assertEquals(201, outcome.cachedStatus());
        assertEquals("{\"status\":\"CREATED\"}", outcome.cachedBody());
    }

    @Test
    @DisplayName("Should detect conflict if concurrent request is currently in progress")
    void shouldDetectConflictIfInProgress() {
        String requestHash = RequestHasher.sha256(payload);
        repository.records.put(tenantId + ":" + idempotencyKey, new IdempotencyRecord(
                tenantId,
                idempotencyKey,
                requestHash,
                IdempotencyStatus.IN_PROGRESS,
                null,
                null,
                Instant.now(),
                Instant.now().plusSeconds(120)
        ));

        IdempotencyOutcome outcome = idempotencyService.checkOrAcquire(tenantId, idempotencyKey, payload);
        assertEquals(IdempotencyOutcome.Type.CONFLICT_IN_PROGRESS, outcome.type());
        assertEquals(409, outcome.cachedStatus());
    }

    @Test
    @DisplayName("Should return 422 if same idempotency key is reused with different payload")
    void shouldDetectMismatchedPayload() {
        String differentPayload = "{\"orderId\":\"ord_999\",\"amount\":5000}";
        String oldPayloadHash = RequestHasher.sha256("different_old_payload");

        repository.records.put(tenantId + ":" + idempotencyKey, new IdempotencyRecord(
                tenantId,
                idempotencyKey,
                oldPayloadHash,
                IdempotencyStatus.COMPLETED,
                201,
                "{}",
                Instant.now(),
                Instant.now().plusSeconds(3600)
        ));

        IdempotencyOutcome outcome = idempotencyService.checkOrAcquire(tenantId, idempotencyKey, differentPayload);
        assertEquals(IdempotencyOutcome.Type.MISMATCHED_PAYLOAD, outcome.type());
        assertEquals(422, outcome.cachedStatus());
    }

    @Test
    @DisplayName("Should pass through if no idempotency key provided")
    void shouldPassThroughWhenNoKey() {
        IdempotencyOutcome outcome = idempotencyService.checkOrAcquire(tenantId, null, payload);
        assertEquals(IdempotencyOutcome.Type.ACQUIRED, outcome.type());

        IdempotencyOutcome emptyOutcome = idempotencyService.checkOrAcquire(tenantId, "   ", payload);
        assertEquals(IdempotencyOutcome.Type.ACQUIRED, emptyOutcome.type());
    }

    private static class FakeIdempotencyRepository extends IdempotencyRepository {
        final Map<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

        public FakeIdempotencyRepository() {
            super(null);
        }

        @Override
        public Optional<IdempotencyRecord> find(String tenantId, String idempotencyKey) {
            return Optional.ofNullable(records.get(tenantId + ":" + idempotencyKey));
        }

        @Override
        public boolean tryAcquire(String tenantId, String idempotencyKey, String requestHash, long ttlSeconds) {
            String compositeKey = tenantId + ":" + idempotencyKey;
            IdempotencyRecord record = new IdempotencyRecord(
                    tenantId,
                    idempotencyKey,
                    requestHash,
                    IdempotencyStatus.IN_PROGRESS,
                    null,
                    null,
                    Instant.now(),
                    Instant.now().plusSeconds(ttlSeconds)
            );
            return records.putIfAbsent(compositeKey, record) == null;
        }

        @Override
        public void complete(String tenantId, String idempotencyKey, int responseStatus, String responseBody) {
            String compositeKey = tenantId + ":" + idempotencyKey;
            IdempotencyRecord current = records.get(compositeKey);
            if (current != null) {
                records.put(compositeKey, new IdempotencyRecord(
                        tenantId,
                        idempotencyKey,
                        current.requestHash(),
                        IdempotencyStatus.COMPLETED,
                        responseStatus,
                        responseBody,
                        current.createdAt(),
                        current.expiresAt()
                ));
            }
        }

        @Override
        public void delete(String tenantId, String idempotencyKey) {
            records.remove(tenantId + ":" + idempotencyKey);
        }
    }
}
