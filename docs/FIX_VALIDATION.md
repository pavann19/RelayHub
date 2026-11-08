> Latest validated implementation: RH-005, commit `cfa7fddea94ae987ed3bf97911b32c35ce74a7f5`, 2026-11-08. **75 tests passed**, current source image built and restart/backup recovery passed. See [RH-VAL-005](validation/RH-VAL-005.json), [gap status](GAP_STATUS.md), and [operations](OPERATIONS.md). Older sections below preserve historical evidence and limitations; RH-004/RH-005 supersede their descriptions of DNS resolution, poison-record retry, subscription deletion and HTTP lock occupancy.

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

## RH-005 final verification (2026-11-08)

The 75-test Java 21 verify run had zero failures, errors or skips, including lease expiry, stale-worker fencing, HTTP outside transactions, lock availability during slow HTTP, migration upgrade/data preservation, retention safety, operations authorization, reconciliation, bounded metrics and single DLQ publication at finalization. The source-built `relayhub:gap-closure` image passed process-kill/replacement recovery, original-payload/idempotency checks and a PostgreSQL logical dump/restore drill. A 60-second local correctness run accepted and delivered 271 additional events, observing zero duplicate receiver acceptances; total committed attempts/acceptances were 272 including the initial recovery event. This is not an endurance or capacity claim.

The isolated recovery containers and network were removed. Compose configuration and Kubernetes YAML parsing passed. Hosted CI, Kubernetes/CNI runtime enforcement, dedicated TLS fixture coverage, long-duration capacity testing and managed production remain UNVERIFIED. Retention of successful audit is deliberately opt-in; quarantined/dead-letter/uncertain work is preserved for operator disposition. Exact commit/image/JAR identities and suite counts are in RH-VAL-005.json.

## Latest portfolio release candidate: RH-007 through RH-013

Historical sections above describe their own revisions. Current evidence is in RH-VAL-008, RH-VAL-009 and RH-VAL-010, with helper/configuration follow-ups in RH-VAL-011 through RH-VAL-013.

- Java 21 verify: 84 tests, zero failures/errors/skips. Python: nine tests passed. Current application bytecode is from commit 6e82a3b99eaf6988e0a55faebbd3fb46176903ba.
- Final application image relayhub:rh010-final and minimal patched edge image relayhub-edge:release-candidate both report zero High/Critical findings with Trivy 0.76.1, including advisories without fixes. CycloneDX/report hashes are preserved. Earlier candidate images failed security checks and were superseded.
- Strict custom-CA/hostname/SNI fixtures, production refusal of unsafe/demo credentials, private provisioning/revocation, OBSERVE restrictions, and real HTTPS/HMAC receiver delivery passed.
- Dedicated single-node Kind/Calico acceptance proved private egress deny/allow/deny and unapproved ingress denial. Final 600-second workload accepted/read back/delivered all 3,000 events, recovered 229 recorded HTTP 503 attempts during a 12-second receiver outage, and replaced one application pod. No lost events or duplicate receiver acceptances were observed in that run.
- The 5/10/20/50 rps ladder delivered all 5,100 unique events with payload and receiver reconciliation. At 20 rps, p95 durable acknowledgement was 59.5 ms and p95 end-to-end delivery 0.875 s. At 50 rps, backlog reached 824 and drained in 17.254 s after intake stopped; this is not demonstrated sustained 50 rps delivery capacity.
- Actual Prometheus/Alertmanager firing and resolved receipts, final-image SIGKILL/replacement and logical backup restore passed. A later external container restart reset in-memory receiver counters; completed reports were preserved and post-resume readiness, HTTPS/HMAC delivery and alert receipts passed again. It was not a planned node-availability experiment.
- Test resources were isolated. The dedicated validation cluster was removed after capture; the existing kind-modelgate cluster/default context, user databases and next_task.txt were preserved. Images and ignored private local reports remain available.

Hosted CI has not run because no remote is configured. Managed database/broker durability, supported target-cluster acceptance, HPA, multi-node availability, real certificate/secret lifecycle, off-site restore objectives and long-term endurance remain release gates. The case study and interview guide preserve these boundaries and provide supported resume wording.
