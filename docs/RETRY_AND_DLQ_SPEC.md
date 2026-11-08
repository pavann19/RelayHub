# Retry, attempt audit and DLQ

Delivery rows start PENDING. The automatic database worker sends pending requests and RETRYING requests whose `next_retry_at` is due. Multiple workers claim work through event-row locks with `SKIP LOCKED`; process termination releases those locks. Retry state is persistent.

- 2xx: SUCCESS.
- 429: retry with Retry-After and jitter unless the attempt budget is exhausted.
- Other 4xx: DEAD_LETTERED immediately.
- 5xx, network failures and other non-success statuses: retry, then dead-letter on exhaustion.

`maxRetries` is the historical API name for the maximum total attempt count, including the initial request. Full jitter samples from zero to `min(maxInterval, initialInterval * multiplier^(attempt-1))`. Retry-After supports delta seconds and HTTP dates, capped at 24 hours to bound extreme values. Redirects are not followed.

Each committed attempt has an immutable snapshot in `webhook_delivery_attempts`. The latest delivery row updates its cumulative attempt number. Event status is recomputed using every persisted delivery, including after a single-subscription retry.

Dead-letter state and the DLQ outbox entry commit with the attempt. Kafka mode publishes the outbox entry to `relayhub.events.dlq`; direct mode keeps the durable database record and logs the notification. A Kafka DLQ publication failure leaves the outbox row pending.

ADMIN replay requires an explicit delivery ID, event ID or `replayAllDeadLetters=true`. Only failed/dead-lettered deliveries are eligible. Replay queues work without waiting for downstream HTTP. Successful deliveries are not resent. A manual replay after exhaustion grants one further attempt; cumulative numbering and prior snapshots remain intact.

Delivery is at least once. A crash after remote acceptance and before committing the attempt can cause a duplicate request with the same delivery ID. Exactly-once remote effects and strict delivery ordering are not provided.

### RH-004 behavior update

Subscription deletion disables future delivery and preserves existing audit/history. Cancelled work does not create a fabricated HTTP attempt. Outbox failures use 30-second deferral and quarantine after five failures; Kafka poison records receive two retries then durable database quarantine. Policy-approved DNS addresses are pinned to each outbound connection while hostname verification remains enabled. JSON API bodies are limited to 1 MiB; oversized requests return 413. These rules supersede earlier descriptions of physical subscription deletion, unlimited poison-record retries, or separate unchecked HTTP DNS resolution. See FIX_VALIDATION.md (under docs/) for evidence boundaries.
