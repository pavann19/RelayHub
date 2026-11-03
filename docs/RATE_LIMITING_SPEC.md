# RelayHub — Rate Limiting & Quota Specification

RelayHub implements a tenant-aware, two-layer rate limiting and quota enforcement engine designed to protect infrastructure from noisy neighbors and downstream targets from denial-of-service spikes.

---

## 1. Algorithm: Token Bucket with Millisecond Replenishment

RelayHub utilizes the **Token Bucket** algorithm for smooth traffic shaping and burst handling:
- **Bucket Capacity (`burstCapacity`)**: The maximum number of tokens a tenant can accumulate. Determines maximum allowable instantaneous traffic burst.
- **Replenishment Rate (`rateLimitPerSecond`)**: Tokens added per second. Replenishment occurs proportionally down to sub-millisecond precision based on elapsed wall-clock time:
  $$\Delta tokens = \min\left(capacity, current\_tokens + \frac{\Delta t_{ms} \times rate}{1000}\right)$$

### Tier Default Configurations

| Tier | Sustained Rate | Burst Capacity | Monthly Quota | Intended Workload |
| :--- | :--- | :--- | :--- | :--- |
| **FREE** | 10 req/s | 20 tokens | 10,000 req/month | Developer sandbox & integration tests |
| **PRO** | 100 req/s | 200 tokens | 500,000 req/month | Production merchant applications |
| **ENTERPRISE**| 1,000 req/s | 2,000 tokens | 10,000,000 req/month | High-volume financial platforms |

---

## 2. Distributed Redis Atomic Lua Script

For distributed multi-instance deployments, token evaluation and consumption is executed atomically in Redis via `src/main/resources/redis/token_bucket.lua`:

```lua
-- Redis Token Bucket Atomic Lua Script
local key_tokens = KEYS[1]
local key_timestamp = KEYS[2]
local rate = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

local current_tokens = tonumber(redis.call('get', key_tokens)) or capacity
local last_refill = tonumber(redis.call('get', key_timestamp)) or now

local elapsed = math.max(0, now - last_refill)
local tokens_to_add = (elapsed * rate) / 1000.0
current_tokens = math.min(capacity, current_tokens + tokens_to_add)

if current_tokens >= requested then
    current_tokens = current_tokens - requested
    redis.call('set', key_tokens, current_tokens, 'EX', 120)
    redis.call('set', key_timestamp, now, 'EX', 120)
    return {1, capacity, math.floor(current_tokens), 1, 0}
else
    local missing = requested - current_tokens
    local retry_after = math.max(1, math.ceil(missing / rate))
    return {0, capacity, 0, 1, retry_after}
end
```

---

## 3. Response Headers Specification

Every response passing through the gateway carries standard rate limiting headers:

```http
HTTP/1.1 201 Created
X-RateLimit-Limit: 200
X-RateLimit-Remaining: 198
X-RateLimit-Reset: 1
```

### On Rate Limit Exhaustion (HTTP 429)

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/problem+json
X-RateLimit-Limit: 200
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1
Retry-After: 3

{
  "type": "about:blank",
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Tenant rate limit or monthly quota exceeded. Please retry after 3 seconds.",
  "instance": "/api/v1/events"
}
```

