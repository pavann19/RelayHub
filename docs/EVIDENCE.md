> Historical evidence retained from the earlier revision. It does not validate the current code or override the limitations in [FIX_VALIDATION.md](FIX_VALIDATION.md). Earlier production-readiness, automatic-retry and performance claims must not be treated as current verified behavior.

# RelayHub — Verification Evidence & Runtime Benchmarks

This document records concrete verification evidence, unit test results, failure-injection assertions, live chaos test outcomes, and performance benchmarks for RelayHub.

---

## 1. Automated Test Suite (Including Live Testcontainers)

The complete automated test suite was executed against Java 21 / Spring Boot with live Docker Testcontainers:

```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.relayhub.controller.EventIngressControllerTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.relayhub.dispatcher.FailureInjectionAndRecoveryTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.relayhub.dispatcher.RetryPolicyCalculatorTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.relayhub.dispatcher.WebhookDispatcherTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.relayhub.idempotency.IdempotencyServiceTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.relayhub.integration.TestcontainersIntegrationTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.relayhub.ratelimit.TokenBucketRateLimiterTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.relayhub.security.ApiKeyHasherTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.relayhub.signing.WebhookSignatureTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 38, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total time: 50.867 s
[INFO] ------------------------------------------------------------------------
```

---

## 2. Invariant Verification Matrix

| Invariant | Test Class / Suite | Assertion Status | Evidence & Behavior |
| :--- | :--- | :---: | :--- |
| **HMAC-SHA256 Signature Authenticity** | `WebhookSignatureTest` | **PASSED** | Constant-time comparison (`MessageDigest.isEqual`) prevents timing attacks. |
| **Payload Tamper Detection** | `WebhookSignatureTest` | **PASSED** | Modified request payload yields signature mismatch rejection. |
| **Replay Attack Tolerance Window** | `WebhookSignatureTest` | **PASSED** | Timestamps older than 300s or future timestamps > 300s rejected. |
| **Token Bucket Burst Limit** | `TokenBucketRateLimiterTest` | **PASSED** | Exactly burst capacity accepted under load; subsequent calls rejected with 429. |
| **Sub-Millisecond Token Replenishment** | `TokenBucketRateLimiterTest` | **PASSED** | Tokens replenish continuously based on elapsed wall-clock milliseconds. |
| **Monthly Quota Enforcement** | `TokenBucketRateLimiterTest` | **PASSED** | Rejects with `Retry-After: 3600` when monthly volume limit is reached. |
| **Multi-Tenant Isolation** | `TokenBucketRateLimiterTest` | **PASSED** | Depleting Tenant A bucket leaves Tenant B with 100% capacity unaffected. |
| **Idempotent Replay** | `IdempotencyServiceTest` | **PASSED** | Duplicate request with matching hash returns cached response with `Idempotent-Replay: true`. |
| **Idempotency Hash Collision Check** | `IdempotencyServiceTest` | **PASSED** | Key reuse with mismatched payload returns HTTP 422 Unprocessable Entity. |
| **Concurrent Idempotency Race** | `IdempotencyServiceTest` | **PASSED** | Concurrent requests with same key detect `IN_PROGRESS` and return 409 Conflict. |
| **Exponential Backoff & Full Jitter** | `RetryPolicyCalculatorTest` | **PASSED** | Decorrelated uniform distribution avoids downstream retry stampedes. |
| **Retry-After Header Parsing** | `RetryPolicyCalculatorTest` | **PASSED** | Correctly parses integer delta-seconds and RFC 1123 HTTP dates. |
| **Downstream 429 Retry Scheduling** | `WebhookDispatcherTest` | **PASSED** | Schedules retry honoring downstream `Retry-After` header. |
| **Immediate DLQ on 4xx Client Error** | `WebhookDispatcherTest` | **PASSED** | Non-retryable 404/401 routed immediately to DLQ without wasting retry quota. |
| **Max Retries Exceeded DLQ** | `WebhookDispatcherTest` | **PASSED** | After max attempts exhausted, marked `DEAD_LETTERED` and sent to Kafka DLQ. |
| **Downstream Outage Recovery** | `FailureInjectionAndRecoveryTest` | **PASSED** | Downstream 503 errors transition to `RETRYING`, succeeding on receiver recovery. |
| **Dead-Letter Replay Engine** | `FailureInjectionAndRecoveryTest` | **PASSED** | Dead-lettered delivery replayed via API and successfully dispatched. |
| **High Concurrency Stability** | `FailureInjectionAndRecoveryTest` | **PASSED** | 40 threads hitting rate limiter simultaneously with zero race conditions. |

---

## 3. Chaos & Fault-Tolerance Verification (`scripts/chaos_and_recovery_test.py`)

Executed live against the running Docker Compose environment:

```
=======================================================
CHAOS TEST 1: Downstream Webhook Receiver Recovery
=======================================================
[1] Stopping mock receiver...
[2] Ingest during downstream outage: status=201
    Event ingested successfully: evt_ed15ebe8-baba-4fd9-b5da-cf317c4790f3
[3] Delivery status during outage: status=500 (Receiver Down)
[4] Resuming downstream mock receiver...
[5] Ingest after receiver recovery: status=201
[PASS] Downstream receiver recovery verified!

=======================================================
CHAOS TEST 2: Kafka Broker Restart Resilience
=======================================================
[1] Restarting Kafka KRaft container...
[2] Waiting for Kafka to become healthy...
    Kafka status: healthy
[3] Ingest after Kafka restart: status=201
[PASS] Kafka broker restart and consumer recovery verified!

=======================================================
CHAOS TEST 3: PostgreSQL Database Restart & HikariCP Reconnect
=======================================================
[1] Restarting PostgreSQL container...
[2] Waiting for Postgres healthcheck...
    Postgres status: healthy
[3] Delivery query after Postgres restart: status=200
[4] Ingest after Postgres restart: status=201
[PASS] PostgreSQL restart and HikariCP pool recovery verified!

=======================================================
CHAOS TEST 4: Redis Outage & Rate Limiter Resilience
=======================================================
[1] Stopping Redis container...
[2] Ingest during Redis outage: status=201
[3] Restarting Redis container...
[PASS] Redis outage handled gracefully!
```

---

## 4. Multi-Instance Cluster Verification (`scripts/test_multi_instance.py`)

Executed across two distinct running container instances (`relayhub-app` on port 8080 and `relayhub-app-2` on port 8081):

```
=======================================================
MULTI-INSTANCE VERIFICATION (Instance 1 & Instance 2)
=======================================================
[1] Verifying instances health...
    Instance 1 (http://localhost:8080) is UP & HEALTHY
    Instance 2 (http://localhost:8081) is UP & HEALTHY

[2] Testing Cross-Instance Idempotent Deduplication...
    Instance 1 response: status=201, rate_remaining=99
    Instance 1 created Event ID: evt_b5622eda-740f-49c3-b4e9-19d3ed3dbe9e
    Instance 2 response: status=201, replay_header=true
    [VERIFIED] Instance 2 successfully deduplicated using shared database!

[3] Testing Cross-Instance Payload Mismatch Protection...
    Instance 2 mismatch response: status=422, body={"detail":"Idempotency key reuse detected with mismatched request payload","status":422,"title":"Idempotency Key Mismatch"}
    [VERIFIED] Instance 2 rejected tampered payload with 422 Unprocessable Entity!

[4] Testing Shared Delivery Audit Trail...
    Deliveries read from Instance 1: 9
    Deliveries read from Instance 2: 9
    [VERIFIED] Both instances read identical delivery states!

=======================================================
MULTI-INSTANCE VERIFICATION PASSED 100%!
=======================================================
```

---

## 5. Live k6 Benchmarks

### 5.1 Sustained Load Benchmark (`bench/k6-load-test.js`)
- **Duration**: 70 seconds across 4 stages (ramp-up to 20 VUs, sustained 50 VUs, peak spike 100 VUs, cool-down).
- **Total Requests Executed**: 7,641 requests.
- **Sustained Throughput**: **109.1 req/s**.
- **HTTP Error Rate**: **0.00%** (0 failed requests out of 7,641).
- **Checks Passed**: **100.00%** (22,923 out of 22,923 assertions passed).
- **Latency Distribution**:
  - Min: 8.93 ms
  - Median: 262.34 ms
  - Average: 362.80 ms
  - p90: 734.01 ms
  - p95: 1,013.00 ms
  - p99: 1,790.00 ms
  - Max: 3,385.00 ms

### 5.2 Rate Limit Blast Benchmark (`bench/k6-rate-limit.js`)
- **Duration**: 10 seconds constant arrival rate at 300 req/s with 100 VUs.
- **Total Requests**: 1,376 requests.
- **Rate-Limited Responses (HTTP 429)**: 748 responses (54.4% clipped as expected past bucket capacity).
- **Header Compliance**:
  - `Retry-After`: 100% present on all 429 responses.
  - `X-RateLimit-Remaining: 0`: 100% verified.
  - RFC 7807 Problem Detail body (`{"title": "Too Many Requests", "status": 429}`): 100% verified.

---

## 6. Docker Container Resource Consumption (`docker stats --no-stream`)

Captured under full active cluster execution:

| Container Name | Service | CPU % | Memory Usage / Limit | Memory % | Network I/O | PIDs |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: |
| `relayhub-app` | RelayHub Instance 1 | 0.44% | 315.2 MiB / 3.829 GiB | 8.04% | 17.8 MB / 117 MB | 45 |
| `relayhub-app-2` | RelayHub Instance 2 | 58.14% | 238.1 MiB / 3.829 GiB | 6.07% | 13.1 MB / 14.2 MB | 42 |
| `relayhub-postgres`| PostgreSQL 16 DB | 11.50% | 86.05 MiB / 3.829 GiB | 2.19% | 25.1 MB / 13.3 MB | 32 |
| `relayhub-kafka` | Apache Kafka KRaft 3.8 | 63.21% | 294.9 MiB / 3.829 GiB | 7.52% | 4.49 MB / 3.72 MB | 139 |
| `relayhub-redis` | Redis 7 Cache | 0.58% | 9.83 MiB / 3.829 GiB | 0.25% | 1.91 kB / 126 B | 6 |
| `relayhub-mock-receiver` | Downstream Mock | 18.00% | 59.81 MiB / 3.829 GiB | 1.53% | 3.06 MB / 6.88 MB | 7 |
| `relayhub-prometheus` | Prometheus Metrics | 0.44% | 31.11 MiB / 3.829 GiB | 0.79% | 90.3 MB / 955 kB | 9 |
| `relayhub-grafana` | Grafana Dashboards | 0.36% | 50.62 MiB / 3.829 GiB | 1.29% | 50.4 kB / 13.1 kB | 17 |
