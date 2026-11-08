# Operations and recovery

## Versioned schema upgrades

Flyway owns schema versions V1 (baseline), V2 (failure quarantine), and V3 (delivery leases). Applied checksums are validated at startup; do not edit an applied migration. Future changes require a new versioned SQL file. Seed SQL remains a separate explicit local-demo option.

Before upgrading an existing database: take and restore-test a PostgreSQL backup, inspect public demo keys, then explicitly set `RELAYHUB_BASELINE_EXISTING=true` for the initial adoption. The baseline version is 0: idempotent baseline SQL runs before additive V2/V3 migrations. Remove that opt-in after adoption. Default startup refuses to silently baseline an existing unversioned schema. The integration suite separately tests upgrading a populated legacy schema while preserving its tenant row.

Review old data through ADMIN `GET /api/v1/operations/legacy-pending`. Explicit `POST /api/v1/operations/legacy-pending/reconcile` queues at most 100 tenant-owned pending events that have neither publication intent nor deliveries. Repeated calls do not duplicate intent. This endpoint does not rewrite historical idempotency hashes or replay already-delivered events. Revoke public demo keys through the existing admin key-revocation API after provisioning a private administrative key. Do not enable demo seeds on a public database.

## Failed work

An outbox failure defers that row for 30 seconds; five failures quarantine it without discarding publication intent. Later rows continue. ADMIN `GET /api/v1/operations/outbox-quarantine` returns at most 100 tenant-owned quarantined items without payloads. `POST /api/v1/operations/outbox-quarantine/{id}/retry` explicitly releases one item after the cause is corrected; the response reports the actual queued count.

Kafka retries a poison record twice, then stores topic/partition/offset/original payload/error type in `kafka_quarantine`. The failed offset is recovered only if that write succeeds. There is no public cross-tenant Kafka-quarantine API. A trusted database operator must inspect and replay the original payload through controlled Kafka tooling after fixing the cause. Never delete quarantine evidence simply to make a backlog metric green. Retention deliberately preserves Kafka/outbox quarantine and dead letters.

## Delivery leases

Runtime delivery uses a short claim transaction, an HTTP request outside the database transaction, and a short fenced finalization transaction. A lease expires after the subscription timeout plus 10 seconds. If a process dies, another worker recovers expired IN_FLIGHT work with the same delivery ID. `webhook_delivery_claims` records each claim and marks an abandoned claim UNKNOWN. An old worker's token cannot overwrite a replacement's result. Completed HTTP attempt snapshots remain separate from uncertain claims.

An already-claimed request can finish when an administrator disables a subscription. Future claims cancel without a request. Cancellation preserves prior attempts. At-least-once side effects remain unavoidable around remote acceptance and local process death; downstream consumers must deduplicate delivery IDs.

## Retention

Expired idempotency rows are removed hourly in batches of 500. Historical event/audit purging is **opt-in**, using `relayhub.retention.enabled=true` and `relayhub.retention.success-days` (default 90, minimum 1). Each run deletes at most 500 old published outbox rows and 100 old successfully delivered events with no non-success deliveries, pending outbox work or live idempotency response. Cascading audit deletion happens only as part of this explicit successful-event retention policy. Disabled retention preserves event/audit history. Dead letters, cancellations, uncertain/non-success work and quarantine are retained for operator disposition; storage alerts remain necessary.

## Observability

Queue gauges expose pending outbox, outbox quarantine, pending/in-flight deliveries, Kafka quarantine, oldest pending outbox age, refresh timestamp and refresh failures. They refresh every five seconds; scrape reads cached values. Alert on stale refresh time, nonzero quarantine, increasing oldest age and sustained pending count. Delivery counters/timers no longer use unbounded tenant/subscription labels; individual tenant audit remains in the authenticated APIs.

Schedulers still use bounded batches; this revision makes no production throughput or long-duration availability claim. Short database transactions remove HTTP lock occupancy but do not constitute a demonstrated scaling result.

## Local recovery and backup drill

Build a local image from the current source and run:

```powershell
docker build -t relayhub:gap-closure .
python scripts/verify-packaged-recovery.py --image relayhub:gap-closure --output target/RH-VAL-005-recovery.json
```

This creates isolated resources, kills the first app after acceptance, replaces it, checks stable delivery/idempotency/payload, and performs a real `pg_dump` / `psql` restore into a second disposable database. It validates restored attempt counts and migration history. It removes only its own containers/volumes/network. This is local logical-backup evidence, not an off-site disaster-recovery or managed-production claim.

## Deployment boundary

TLS termination, trusted secret provisioning, network-policy enforcement and external alert routing must be configured in the actual target environment. DNS pinning closes the application lookup/connection gap; default TLS hostname verification remains enabled. Kubernetes and hosted CI require separate environment validation and are not inferred from local tests. No push or external deployment is authorized by these local changes.

The reference `deploy/k8s/09-relayhub-egress.yaml` limits app egress to cluster DNS, same-namespace PostgreSQL/Kafka, and public IPv4 HTTPS destinations. It excludes private/reserved ranges and denies IPv6/non-443 destinations by omission. Local Calico validation separately proves private HTTP deny/allow/deny and rejects unapproved application ingress; the TLS receiver requires a narrow explicit exception. Operators must adapt and revalidate policy for the real CNI, node-local DNS, approved private integrations and IPv6. See RH-VAL-008 for exact local scope; target-environment enforcement is not inferred.

## Production-oriented release candidate

Use the explicit production profile and private provisioning procedure in PRODUCTION_PROFILE.md. The NGINX TLS edge terminates HTTPS; application and edge images have separate vulnerability gates. Startup probes permit bounded slow initialization; liveness remains independent of PostgreSQL and readiness includes it. Prometheus uses a mounted OBSERVE token, discovers each application pod through a headless service, and evaluates quarantine/backlog/stale-monitor/failed-scrape rules. The local Alertmanager drill verifies both firing and resolved receipts without contacting a person or external integration.

The reference HPA ceiling and connection pool cap budget 64 application database connections at eight replicas. Reserve migration/admin/monitoring headroom and recompute for the actual database. HPA and multi-node availability are not claimed by two-replica local tests. The evidence ledger and case study distinguish functional workload checks, patched artifact scans, hosted verification and production acceptance.
