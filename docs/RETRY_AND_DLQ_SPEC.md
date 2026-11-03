# RelayHub — Retry Policy, Dead-Letter Queue (DLQ) & Replay Engine

RelayHub guarantees reliable, resilient webhook delivery under temporary network hiccups, downstream server reboots, and rate limiting backpressure, while avoiding endless futile retries on client errors.

---

## 1. Retry Backoff Formula with Full Jitter

To prevent the **Thundering Herd** problem where recovering downstream targets are overwhelmed by synchronized retries, RelayHub implements **Exponential Backoff with Full Jitter**:

$$Delay = \text{random}\left(\frac{\text{bounded\_delay}}{2}, \text{bounded\_delay}\right)$$
$$\text{where}\quad \text{bounded\_delay} = \min\left(MaxInterval, InitialInterval \times Multiplier^{\text{attempt}-1}\right)$$

### Default Policy:
- `InitialInterval`: 1,000 ms (1 second)
- `Multiplier`: 2.0
- `MaxInterval`: 60,000 ms (60 seconds)
- `MaxRetries`: 5 attempts

| Attempt | Base Delay | Range with Full Jitter |
| :---: | :---: | :---: |
| **1** | Immediate | 0 ms |
| **2** | 1,000 ms | 500 ms – 1,000 ms |
| **3** | 2,000 ms | 1,000 ms – 2,000 ms |
| **4** | 4,000 ms | 2,000 ms – 4,000 ms |
| **5** | 8,000 ms | 4,000 ms – 8,000 ms |

---

## 2. Downstream Response Classification Matrix

| HTTP Status / Condition | Classification | Action Taken |
| :--- | :--- | :--- |
| **2xx (200-299)** | **Success** | Marked `SUCCESS`. Latency and success metrics recorded. Event status becomes `DELIVERED`. |
| **429 Too Many Requests** | **Rate Limited** | Parses `Retry-After` header. If `attempt < maxRetries`, schedules retry honoring `Retry-After`. If `attempt >= maxRetries`, routes to DLQ. |
| **4xx (except 408, 429)**<br/>*(e.g., 400, 401, 403, 404, 422)* | **Non-Retryable Client Error** | **Immediately DEAD_LETTERED**. Published to `relayhub.events.dlq`. No futile retries against permanent client configuration errors. |
| **5xx (500, 502, 503, 504)** | **Transient Server Error** | If `attempt < maxRetries`, marked `RETRYING` with exponential backoff. If `attempt >= maxRetries`, routed to DLQ. |
| **Connection Timeout / Socket Exception** | **Network Transient** | If `attempt < maxRetries`, marked `RETRYING` with exponential backoff. If `attempt >= maxRetries`, routed to DLQ. |

---

## 3. Dead-Letter Queue (DLQ) & Operator Replay

When an event exceeds configured maximum retry attempts or encounters a non-retryable 4xx client error:
1. Marked `status = DEAD_LETTERED` in `webhook_deliveries` with full audit details (error message, status code, latency, headers).
2. Published to Kafka topic `relayhub.events.dlq` for offline inspection and alarm notification.
3. Event is visible via `GET /api/v1/deliveries/dead-letters`.

### Replay / Redrive API (`POST /api/v1/deliveries/replay`)
Once downstream outages are resolved or subscribers update their endpoints, operators or automated recovery systems can re-drive failed events:

#### Example 1: Replay Specific Delivery
```http
POST /api/v1/deliveries/replay HTTP/1.1
Content-Type: application/json
X-API-Key: rlh_live_...

{
  "deliveryId": "del_01948b8e-d901-729c-99f5-7489a3cf1204"
}
```

#### Example 2: Replay Specific Event
```json
{
  "eventId": "evt_01948b8e-d901-729c-99f5-7489a3cf1201"
}
```

#### Example 3: Bulk Replay All Dead Letters for Tenant
```json
{
  "replayAllDeadLetters": true
}
```
Replay increments the execution counter, resets failure state, and re-enqueues the event into the delivery pipeline.
