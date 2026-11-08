# Reliability and authorization fix validation

Date: 2026-11-07 (Asia/Kolkata). Scope: local working tree in `D:/Apple`; no public push or deployment.

## Implemented changes

- ADMIN authorization for subscription/key management and replay; only INGEST/ADMIN roles are accepted. Metrics are protected and health details are restricted.
- Tenant isolation for delivery listing by event ID and the new attempt-history endpoint.
- Transactional event/idempotency/outbox persistence; ingestion failures roll back all three. Idempotency binds event type and payload, handles expired records atomically and retains completed responses for 24 hours. Original webhook payloads remain unchanged.
- A durable outbox publisher waits for Kafka acknowledgement. Kafka listener failures propagate to unlimited retries instead of being swallowed; offsets commit after successful processing.
- Persistent pending/due delivery workers coordinate across instances with PostgreSQL event-row locks and `SKIP LOCKED`. Duplicate notifications reuse deliveries. Manual replay queues only failed/dead-lettered deliveries and requires an explicit selector.
- Cumulative attempt counts update correctly; immutable per-attempt snapshots are stored separately. Aggregate event status considers every delivery after an individual retry.
- Shared PostgreSQL token buckets and atomic UTC monthly quotas replace the runtime in-memory limiter. State survives process restarts. Redis is not used by this implementation.
- Exact-host destination allowlisting, private-address restrictions, disabled redirects, bounded response previews/body completion, and sanitization of binary null bytes before PostgreSQL audit persistence.
- Demo credentials are no longer seeded by default. Compose explicitly enables its local seeds/receiver; SQL initialization fails on errors. README/specifications distinguish implemented behavior from historical claims.

## Fresh verification

| Check | Result | Evidence |
| --- | --- | --- |
| Java 21 `mvnw verify` | PASS | 54 tests, 0 failures, 0 errors, 0 skipped; executable JAR packaged |
| Unit/controller/signature/policy checks | PASS | 39 tests within the full suite |
| Live automatic-delivery integration | PASS | 6 tests with an isolated PostgreSQL container and real HTTP receiver |
| Live Kafka/database/API integration | PASS | 9 tests with isolated PostgreSQL and Kafka containers |
| Packaged application process-kill/replacement | PASS | Standalone `relayhub:verified-fixes`: accepted event recovered, exactly one committed attempt/receiver acceptance, original payload and idempotency preserved |
| Recovery environment cleanup | PASS | All recovery-test containers and networks removed |
| Compose configuration validation | PASS | `docker compose -f deploy/docker-compose.yml config --quiet` |
| Whitespace/diff validation | PASS | `git diff --check` |
| Hosted CI / Kubernetes / managed production | UNVERIFIED | Not run or deployed in this task |

The integration tests cover actual scheduled retries through 503 recovery, DLQ/replay, per-attempt SQL history, multi-subscription aggregation, competing workers, rollback after remote acceptance, stable delivery IDs, redirects/body limits, management authorization, cross-tenant reads, atomic ingestion failure, expired idempotency, shared quota races/month rollover, persisted token refill and an injected publication failure followed by real Kafka publication/duplicate notification handling. An explicit assertion checks that the stored payload is the original payload rather than the idempotency hash input.

The self-contained local image is `relayhub:verified-fixes`. Its JAR SHA-256 is `7114d44919997ff9596579971e315aa5f139245adad5a6f1fd4671e2762a9121`. The image was assembled from the fully verified JAR in the previously source-built Java 21 runtime image. A cold source-image build after the last payload correction was not repeated because of the local disk-space constraint. The final standalone image itself passed the process-kill/replacement test without a mounted JAR.

[Machine-readable verification summary](validation/fix-validation-2026-11-07.json) records suite counts, artifact/image identities, recovery assertions and cleanup.

Raw local outputs: `target/fix-validation-final.log`, `target/surefire-reports/`, `target/standalone-image-recovery.json`, `target/runtime-image-build.log`, and the packaged JAR under `target/`. These generated files are not committed. The test socket directory uses the build directory to avoid a Windows Java AF_UNIX failure observed with an 8.3 TEMP path; application settings outside tests are unchanged by that workaround.

## Reproduce

With Java 21 and Docker available:

```powershell
.\mvnw.cmd verify
docker compose -f deploy/docker-compose.yml config --quiet
docker build -t relayhub:fix-validation .
python scripts/verify-packaged-recovery.py --image relayhub:fix-validation
```

The recovery script creates isolated containers, a private network and a temporary database. It accepts an event while workers are disabled, kills that application process, starts a replacement with workers enabled, and checks delivery, attempt history, original payload bytes and idempotency after restart. It removes only its own containers/network. `--jar target/relayhub-service-1.0.0-SNAPSHOT.jar` can mount a freshly verified JAR read-only into the runtime image when avoiding an additional image build.

## Limits and upgrade notes

- Delivery is at least once. A remote acceptance followed by a local rollback/crash can cause another request with the same delivery ID; receiver deduplication remains necessary.
- The hostname allowlist is operator-controlled. DNS validation is separate from Java HTTP resolution; network-level egress restrictions are still required against DNS rebinding. Private destinations remain opt-in.
- A worker holds a database connection/event lock during HTTP. No current throughput, latency, availability or production-capacity claim is made. Poison Kafka records can block their partition under unlimited retry.
- Snapshot history starts with this revision. Old overwritten attempts cannot be reconstructed, and deleting a subscription retains the existing cascading-delete behavior. Establish a retention policy before deployment.
- Existing databases may retain public demo keys, old idempotency hashes, or pre-upgrade PENDING events without an outbox record. Revoke demo keys and explicitly reconcile old events; the additive schema does not silently rewrite historical data. See DEPLOYMENT.md.
- JWT/OIDC, tracing propagation, production TLS/secret management and hosted operational validation remain outside the implemented scope. Historical benchmark/evidence text is retained and marked historical in EVIDENCE.md.

## Follow-up: signature verifier boundary checks (2026-11-07)

A small follow-up hardens the standalone Java signature verifier. Timestamp distance now uses checked subtraction, rejecting overflow instead of letting `Math.abs(Long.MIN_VALUE)` pass the replay-window check. Duplicate `t` or `v1` fields and negative tolerance settings are rejected. Exact past/future tolerance boundaries still pass. Signing format and outbound payloads are unchanged.

The failure requires a correctly signed header; this is replay-window/parser hardening, not evidence of signature forgery or an exposed inbound authentication bypass. The application uses this utility for verification examples/tests; outbound delivery signing is unchanged.

Regression evidence: the expanded signature suite reproduced two failures before the fix, then passed all 9 tests with zero failures/errors/skips on Java 21 (`mvnw -o test -Dtest=WebhookSignatureTest`). Logs: `target/signature-before.log` and `target/signature-after.log`. The earlier 54-test full-suite and packaged-image results above belong to the preceding revision; they were not rerun or rebuilt for this isolated helper change.

## RH-004: high-priority safety and isolation (2026-11-08)

Subscription DELETE now deactivates the subscription; audit rows remain. Pending work for an inactive subscription becomes CANCELLED without an HTTP attempt, and replay is rejected. Repeated outbox failures back off 30 seconds and enter durable quarantine after five failures so later records can progress. Worker transaction failures defer that delivery for 30 seconds. Kafka failures retry twice before the original record is stored in PostgreSQL quarantine; a failed quarantine write prevents successful recovery/offset handling.

Outbound HTTP now pins each connection to the exact policy-approved addresses using Apache HttpClient 5.5.2. The original hostname remains in the URI for Host, TLS certificate verification and SNI. Redirects, automatic HTTP retries and cookies are disabled; a cancellation deadline bounds each request. Network egress restrictions remain defense in depth, rather than compensating for a second unchecked HTTP DNS lookup.

API request bodies are capped at 1 MiB including unknown/chunked lengths. Field limits match database columns; control characters in event types and null bytes in text payloads are rejected. Event replay reports zero when no eligible delivery was queued.

Validation and commit identity are recorded in CHANGE_LEDGER.md and RH-VAL-004.json after tests complete. This update does not claim hosted CI, Kubernetes runtime, TLS fixture coverage, or new throughput measurements.
