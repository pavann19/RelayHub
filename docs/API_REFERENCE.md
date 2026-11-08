# API reference

Interactive documentation is served at `/swagger-ui.html` and `/v3/api-docs` in development and disabled in the production profile. Authenticate with `X-API-Key`, or `Authorization: Bearer <rlh_... key>`. Bearer support here is for API keys, not JWTs. Prometheus/metrics require OBSERVE or ADMIN; OBSERVE has no event or key-management access.

| Method/path | Behavior | Role |
| --- | --- | --- |
| POST /api/v1/events | Ingest `{eventType,payload}` with optional Idempotency-Key | INGEST/ADMIN |
| GET /api/v1/events or /{id} | Tenant event metadata | INGEST/ADMIN |
| POST/GET /api/v1/subscriptions | Create/list tenant subscriptions | ADMIN |
| GET/DELETE /api/v1/subscriptions/{id} | Read/delete tenant subscription | ADMIN |
| POST/GET /api/v1/api-keys | Create/list tenant keys | ADMIN |
| DELETE /api/v1/api-keys/{id} | Revoke tenant key | ADMIN |
| GET /api/v1/deliveries | Tenant latest delivery records; optional eventId | INGEST/ADMIN |
| GET /api/v1/deliveries/{id} | Tenant latest delivery record | INGEST/ADMIN |
| GET /api/v1/deliveries/{id}/attempts | Immutable tenant-scoped attempt history | INGEST/ADMIN |
| GET /api/v1/deliveries/dead-letters | Tenant dead-lettered records | INGEST/ADMIN |
| POST /api/v1/deliveries/replay | Explicitly queue failed/dead-lettered work | ADMIN |

Ingestion returns 201 after durable database commit. An idempotent replay returns the original status/body with `Idempotent-Replay: true`; payload/event-type mismatch returns 422 and an in-progress conflict returns 409. The cached response is the ingestion response, not a live delivery status. Idempotency keys expire 24 hours after completion.

Subscription fields include targetUrl, optional secret (16–255 characters), comma-separated eventTypes, maxRetries (maximum total attempts) and timeoutMs. The operator must allow the destination host before registration. A generated signing secret is returned by subscription APIs; restrict admin credential access accordingly.

Replay bodies contain a deliveryId, eventId, or `replayAllDeadLetters:true`. Empty bodies do not trigger bulk replay. `subscriptionId` filtering is not supported. Bulk/event replay skips successful deliveries. Standard validation errors return 400, permission failures 403, and rate/quota exhaustion 429.

### Operations APIs (ADMIN only)

- `GET /api/v1/operations/outbox-quarantine`: up to 100 tenant-owned items.
- `POST /api/v1/operations/outbox-quarantine/{id}/retry`: actual queued count, zero when ineligible.
- `GET /api/v1/operations/legacy-pending`: preview tenant-owned events missing publication/delivery work.
- `POST /api/v1/operations/legacy-pending/reconcile`: queue at most 100 missing intents without duplicates.

Subscription DELETE now deactivates delivery without deleting audit. A future worker claim becomes CANCELLED; a request already in flight can complete. Pending delivery attempt number is zero until an attempt is claimed. Lease claims, including UNKNOWN outcomes, are preserved separately from completed HTTP snapshots.
