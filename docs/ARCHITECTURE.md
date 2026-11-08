# Architecture

The API is a Spring MVC/JDBC service with API-key authentication. PostgreSQL is the system of record for tenants, keys, subscriptions, events, idempotency, rate limits, delivery state, attempt history and the publication outbox.

Ingestion commits an event, its idempotency response and an outbox row in one transaction. The outbox worker publishes only committed work. Kafka sends wait for acknowledgement; the outbox row stays pending if publication fails. A crash between acknowledgement and marking publication can duplicate a notification. The direct transport synchronously materializes delivery rows instead of using an in-memory executor queue.

Materialization locks the event, creates pending delivery rows and records aggregate status. Duplicate notifications reuse persisted deliveries. Kafka listener exceptions receive two retries before a poison record is durably quarantined in PostgreSQL. A quarantine persistence failure prevents acknowledgement. Outbox failures defer for 30 seconds and quarantine after five failures; ADMIN operations inspect and explicitly release them.

A scheduled worker selects due deliveries and claims each under a fencing token in a short transaction. HTTP runs after the transaction releases its connection and locks. Finalization checks that the token still owns the claim. An expired claim is recorded as UNKNOWN and can be recovered; a stale worker cannot overwrite a new claim's outcome. The external HTTP side effect cannot be rolled back; receivers must deduplicate stable delivery IDs.

Delivery attempts are append-only JSON snapshots keyed by delivery ID and cumulative attempt number. The latest row drives scheduling. Aggregate event status reads all subscriptions' persisted delivery states after a retry.

The scheduler processes bounded batches. Slow destinations occupy worker time, so fairness, destination isolation and capacity require workload-specific measurement. PostgreSQL row locks serialize quota updates per tenant. Redis is not used by the runtime limiter. Direct mode removes broker infrastructure; Kafka mode adds a partitioned transport while PostgreSQL remains the recovery authority.

### RH-004 behavior update

Subscription deletion disables future delivery and preserves existing audit/history. Cancelled work does not create a fabricated HTTP attempt. Outbox failures use 30-second deferral and quarantine after five failures; Kafka poison records receive two retries then durable database quarantine. Policy-approved DNS addresses are pinned to each outbound connection while hostname verification remains enabled. JSON API bodies are limited to 1 MiB; oversized requests return 413. These rules supersede earlier descriptions of physical subscription deletion, unlimited poison-record retries, or separate unchecked HTTP DNS resolution. See FIX_VALIDATION.md (under docs/) for evidence boundaries.

### Current lease implementation (RH-005)

`webhook_delivery_claims` preserves uncertain outcomes on lease expiry. Logical backup/restore, bounded optional successful-event retention, migration checksums and queue health are described in OPERATIONS.md. The production profile enforces HTTPS targets and refuses unsafe bootstrap defaults. The TLS edge and enforcing CNI provide independent network boundaries; application pods do not receive service-account tokens or writable root filesystems.
