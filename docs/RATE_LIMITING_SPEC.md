# Rate limiting and quotas

`JdbcTokenBucketRateLimiter` is the active implementation. The in-memory limiter is retained for unit tests and is not registered as a runtime component. Redis and its historical Lua script are not used by the active limiter.

A transaction inserts the tenant bucket if absent, locks it, reads database time, replenishes tokens, checks the UTC monthly quota, and writes tokens/usage together. This serializes quota checks and increments across application instances. Bucket state and usage persist across restarts. The quota month changes lazily on the next request after a UTC calendar-month boundary.

All authenticated `/api/` requests consume one token and quota unit, including reads, rejected business operations and idempotent replays. Unauthorized management requests may consume quota before authorization. Rejected rate/quota requests do not consume units. Responses expose `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`; rejections also include `Retry-After`. Monthly exhaustion returns the time until the next UTC month. There is no automatic in-memory fallback during a database outage.

Limits come from the authenticated tenant record. The implementation favors shared correctness over an unmeasured throughput target; each request requires a short database transaction and a tenant-row lock.
