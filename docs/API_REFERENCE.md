# RelayHub — OpenAPI & REST API Reference

RelayHub exposes fully compliant OpenAPI 3.0 endpoints. When running locally, interactive documentation is accessible at `http://localhost:8080/swagger-ui.html` and raw JSON at `http://localhost:8080/v3/api-docs`.

---

## Headers Summary

| Header | Required | Direction | Description |
| :--- | :---: | :---: | :--- |
| `X-API-Key` | Conditional | Inbound | Tenant API key (`rlh_live_...`). Required if Bearer token absent. |
| `Authorization` | Conditional | Inbound | `Bearer <JWT_TOKEN>` for OAuth2/OIDC. |
| `Idempotency-Key` | Optional | Inbound | Client-generated UUID or hash for deduplication. |
| `Idempotent-Replay` | Automatic | Outbound | Present (`true`) if response was served from cache. |
| `X-RateLimit-Limit` | Automatic | Outbound | Tenant's maximum burst capacity. |
| `X-RateLimit-Remaining` | Automatic | Outbound | Available tokens in current window. |
| `X-RateLimit-Reset` | Automatic | Outbound | Seconds until window refresh. |
| `Retry-After` | On 429 | Outbound | Seconds to wait before retrying request. |
| `X-RelayHub-Signature`| Automatic | Webhook | `t={timestamp},v1={hmac}` on outbound deliveries. |
| `X-RelayHub-Event` | Automatic | Webhook | Original event type (e.g. `order.created`). |
| `X-RelayHub-Delivery` | Automatic | Webhook | Unique delivery attempt ID (`del_...`). |

---

## 1. Event Ingestion (`POST /api/v1/events`)

### Request
```http
POST /api/v1/events HTTP/1.1
Host: localhost:8080
Content-Type: application/json
X-API-Key: rlh_live_demo1234567890abcdef
Idempotency-Key: idem_01948b8e-d901

{
  "eventType": "payment.completed",
  "payload": {
    "orderId": "ord_99981",
    "amount": 89.99,
    "currency": "EUR",
    "customer": "Anna Becker"
  }
}
```

### Response (201 Created)
```json
{
  "eventId": "evt_8f0a21cf-90f1-432d-961a-f7324b910129",
  "tenantId": "tenant_default",
  "eventType": "payment.completed",
  "status": "PENDING",
  "matchedSubscriptions": 2,
  "createdAt": "2026-11-03T11:45:00.123Z"
}
```

---

## 2. Webhook Subscriptions

### Create Subscription (`POST /api/v1/subscriptions`)
```http
POST /api/v1/subscriptions HTTP/1.1
Content-Type: application/json
X-API-Key: rlh_live_demo1234567890abcdef

{
  "targetUrl": "https://api.merchant.com/webhooks/relayhub",
  "eventTypes": "payment.completed,order.*",
  "maxRetries": 5,
  "timeoutMs": 5000
}
```

### Response (201 Created)
```json
{
  "id": "sub_41029cfa-1182-42fe-8f0a-7489a3cf1202",
  "tenantId": "tenant_default",
  "targetUrl": "https://api.merchant.com/webhooks/relayhub",
  "secret": "sec_live_9f86d081884c7d659a2feaa0c55ad015",
  "eventTypes": "payment.completed,order.*",
  "isActive": true,
  "maxRetries": 5,
  "timeoutMs": 5000,
  "createdAt": "2026-11-03T11:46:12Z"
}
```

---

## 3. Delivery Tracking & DLQ Inspection

- `GET /api/v1/deliveries` — List recent delivery attempts for tenant.
- `GET /api/v1/deliveries?eventId=evt_...` — Filter deliveries by event.
- `GET /api/v1/deliveries/{id}` — Get full delivery audit details (headers, status, latency).
- `GET /api/v1/deliveries/dead-letters` — Query all dead-lettered events awaiting intervention.

---

## 4. Replay API (`POST /api/v1/deliveries/replay`)

```http
POST /api/v1/deliveries/replay HTTP/1.1
Content-Type: application/json
X-API-Key: rlh_live_demo1234567890abcdef

{
  "replayAllDeadLetters": true
}
```

### Response (200 OK)
```json
{
  "replayedCount": 4,
  "eventIds": [
    "evt_8f0a21cf-90f1-432d-961a-f7324b910129",
    "evt_7b19dc02-8812-42de-91a0-e83719028120"
  ],
  "message": "Bulk replay processed for 4 events"
}
```
