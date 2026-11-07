# Architecture

The API is a Spring MVC/JDBC service with API-key authentication. PostgreSQL is the system of record for tenants, keys, subscriptions, events, idempotency, rate limits, delivery state, attempt history and the publication outbox.

Ingestion commits an event, its idempotency response and an outbox row in one transaction. The outbox worker publishes only committed work. Kafka sends wait for acknowledgement; the outbox row stays pending if publication fails. A crash between acknowledgement and marking publication can duplicate a notification. The direct transport synchronously materializes delivery rows instead of using an in-memory executor queue.

Materialization locks the event, creates pending delivery rows and records aggregate status. Duplicate notifications reuse persisted deliveries. Kafka listener exceptions propagate to an unlimited retry handler, and offsets commit after successful handling. A poison record can therefore block its partition; operational quarantine tooling remains future work.

A scheduled worker selects events with pending/due deliveries using `FOR UPDATE SKIP LOCKED`. Event locks serialize materialization, retries and replay across replicas. HTTP attempts and their database result commit in the same worker transaction. A sender crash rolls back state and releases locks, allowing another worker to recover. The external HTTP side effect cannot be rolled back; receivers must deduplicate stable delivery IDs.

Delivery attempts are append-only JSON snapshots keyed by delivery ID and cumulative attempt number. The latest row drives scheduling. Aggregate event status reads all subscriptions' persisted delivery states after a retry.

Trade-off: each attempt holds a database connection and event lock during the bounded HTTP request. The initial scheduler processes bounded batches and is intended for correctness validation, not an unmeasured capacity claim. PostgreSQL row locks also serialize quota updates per tenant. Redis is not used by the runtime limiter.
