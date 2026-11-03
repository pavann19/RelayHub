# RelayHub — Architecture & System Design

RelayHub is a high-reliability, cloud-native API Gateway & Webhook Delivery Platform designed for enterprise multi-tenant infrastructure. It decouples high-throughput ingress event ingestion from resilient, asynchronous outbound webhook delivery with strict at-least-once delivery, idempotency guarantees, and rate limit protections.

---

## 1. High-Level Architecture

```mermaid
flowchart TD
    Client["External Client / API Ingestor"] -->|HTTPS + API Key / JWT| Ingress["RelayHub Ingress API Gateway"]

    subgraph "API Gateway Pipeline"
        Auth["1. AuthN & Tenant Context (API Key / OAuth2)"]
        RateLimit["2. Rate Limiter (Token Bucket & Quotas)"]
        Idempotency["3. Idempotency Gate (SHA-256 Payload Hash)"]
        Ingress --> Auth
        Auth --> RateLimit
        RateLimit --> Idempotency
    end

    Idempotency -->|Persist Event & Deduplicate| DB[(PostgreSQL 16)]
    Idempotency -->|Publish Event| KafkaBus[("Apache Kafka<br/>topic: relayhub.events")]

    subgraph "Webhook Dispatcher Engine"
        Consumer["Kafka Partition Consumer<br/>(Tenant-keyed)"]
        Matcher["Subscription Matcher"]
        Signer["HMAC-SHA256 Signer"]
        Worker["Virtual Thread HTTP Dispatcher<br/>(Connect 5s / Read 10s)"]
        Backoff["Retry Policy Engine<br/>(Exponential Backoff + Full Jitter)"]
        DLQProducer["DLQ Publisher"]

        KafkaBus --> Consumer
        Consumer --> Matcher
        Matcher --> Signer
        Signer --> Worker
        Worker -->|2xx Success| AuditSuccess[(Delivery Audit: SUCCESS)]
        Worker -->|429 / 5xx / Timeout| Backoff
        Backoff -->|attempt < maxRetries| Worker
        Backoff -->|attempt >= maxRetries| DLQProducer
        Worker -->|4xx Non-retryable| DLQProducer
        DLQProducer --> KafkaDLQ[("Apache Kafka DLQ<br/>topic: relayhub.events.dlq")]
    end

    Worker -->|HTTPS Webhook + X-RelayHub-Signature| Downstream["External Webhook Subscribers"]
    KafkaDLQ --> ReplayAPI["Replay / Redrive Controller"]
    ReplayAPI -->|Operator Re-enqueue| Consumer
```

---

## 2. Ingress Pipeline Breakdown

Every incoming request to `POST /api/v1/events` passes through four discrete pipeline stages:

1. **Authentication & Tenant Isolation (`ApiKeyAuthenticationFilter`)**:
   - Inspects `X-API-Key` or `Authorization: Bearer rlh_...`.
   - Computes deterministic SHA-256 hash of the key and queries `api_keys` table.
   - Verifies `is_active = TRUE` and expiration timestamp.
   - Populates `TenantContext` on the request thread.

2. **Tenant-Aware Rate Limiting & Quotas (`RateLimitFilter`)**:
   - Evaluates tenant's current token bucket and monthly consumption counter.
   - Injects standard RFC rate limiting headers: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`.
   - On limit exhaustion: sets `Retry-After: {seconds}` and immediately returns `429 Too Many Requests` formatted as an RFC 7807 `ProblemDetail`.

3. **Ingestion Idempotency Gate (`IdempotencyService`)**:
   - Reads `Idempotency-Key` header (or JSON field).
   - Computes `SHA-256(request_payload)`.
   - If key exists with matching hash and `COMPLETED`: returns previously cached response with header `Idempotent-Replay: true`.
   - If key exists with matching hash and `IN_PROGRESS`: returns `409 Conflict`.
   - If key exists with **different** hash: returns `422 Unprocessable Entity` ("Mismatched payload").
   - If key does not exist: acquires atomic lock in `idempotency_records` table with a 120-second lease.

4. **Persistence & Kafka Publishing**:
   - Persists immutable event record in `events` table with status `PENDING`.
   - Publishes `EventEnvelope` to Kafka topic `relayhub.events`.
   - Uses `tenant_id` as the Kafka message key, guaranteeing strict partition-level in-order delivery per tenant while scaling horizontally across cluster partitions.

---

## 3. Webhook Delivery Engine

The webhook dispatcher operates asynchronously with zero blocking overhead using Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`).

### Event Lifecycle State Machine

```mermaid
stateDiagram-v2
    [*] --> PENDING: Event Ingested
    PENDING --> PROCESSING: Matched active subscriptions
    PROCESSING --> DELIVERED: All delivery attempts return HTTP 2xx
    PROCESSING --> RETRYING: Downstream returns 5xx, 429, or network timeout
    RETRYING --> PROCESSING: Next backoff timer fires
    RETRYING --> DEAD_LETTERED: Attempts reach maxRetries
    PROCESSING --> DEAD_LETTERED: Downstream returns non-retryable 4xx (e.g. 404, 401)
    DEAD_LETTERED --> PROCESSING: Replay API invoked
    DELIVERED --> [*]
```

---

## 4. Database Schema (PostgreSQL 16)

```
+--------------------+        +---------------------+
|      tenants       |        |      api_keys       |
+--------------------+        +---------------------+
| id (PK)            |<-------| id (PK)             |
| name               |        | tenant_id (FK)      |
| tier               |        | key_prefix          |
| rate_limit_per_sec |        | key_hash (INDEX)    |
| burst_capacity     |        | role                |
| monthly_quota      |        | is_active           |
| created_at         |        | expires_at          |
+--------------------+        +---------------------+
          |
          |                   +---------------------+
          +------------------>|    subscriptions    |
          |                   +---------------------+
          |                   | id (PK)             |
          |                   | tenant_id (FK)      |
          |                   | target_url          |
          |                   | secret (HMAC)       |
          |                   | event_types         |
          |                   | is_active           |
          |                   | max_retries         |
          |                   | initial_interval_ms |
          |                   | backoff_multiplier  |
          |                   | timeout_ms          |
          |                   +---------------------+
          |
          v
+--------------------+        +----------------------+
|       events       |        |  webhook_deliveries  |
+--------------------+        +----------------------+
| id (PK)            |<-------| id (PK)              |
| tenant_id (FK)     |        | event_id (FK)        |
| event_type         |        | subscription_id (FK) |
| idempotency_key    |        | attempt_number       |
| payload (TEXT/JSON)|        | status               |
| status             |        | http_status          |
| created_at         |        | request_headers      |
+--------------------+        | response_body        |
                              | latency_ms           |
                              | next_retry_at        |
                              +----------------------+
```
