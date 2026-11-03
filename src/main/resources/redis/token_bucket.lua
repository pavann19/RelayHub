-- Redis Token Bucket Rate Limiter Script
-- KEYS[1] = bucket key (e.g. "ratelimit:tokens:{tenant_id}")
-- KEYS[2] = timestamp key (e.g. "ratelimit:timestamp:{tenant_id}")
-- ARGV[1] = rate (tokens replenished per second)
-- ARGV[2] = burst_capacity (maximum tokens bucket can hold)
-- ARGV[3] = current_timestamp_ms
-- ARGV[4] = requested_tokens (typically 1)

local key_tokens = KEYS[1]
local key_timestamp = KEYS[2]

local rate = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

-- TTL to auto-expire idle tenant keys (2 x time to completely fill bucket, minimum 60s)
local ttl = math.max(60, math.ceil((capacity / rate) * 2))

local current_tokens = tonumber(redis.call('get', key_tokens))
local last_refill = tonumber(redis.call('get', key_timestamp))

if current_tokens == nil then
    current_tokens = capacity
    last_refill = now
else
    local elapsed = math.max(0, now - last_refill)
    local tokens_to_add = (elapsed * rate) / 1000.0
    current_tokens = math.min(capacity, current_tokens + tokens_to_add)
    last_refill = now
end

if current_tokens >= requested then
    current_tokens = current_tokens - requested
    redis.call('set', key_tokens, current_tokens, 'EX', ttl)
    redis.call('set', key_timestamp, last_refill, 'EX', ttl)
    local remaining = math.floor(current_tokens)
    -- Return: 1 (allowed), limit, remaining, reset_seconds, retry_after
    return {1, capacity, remaining, 1, 0}
else
    local missing = requested - current_tokens
    local retry_after = math.max(1, math.ceil(missing / rate))
    redis.call('set', key_tokens, current_tokens, 'EX', ttl)
    redis.call('set', key_timestamp, last_refill, 'EX', ttl)
    -- Return: 0 (denied), limit, 0, 1, retry_after
    return {0, capacity, 0, 1, retry_after}
end

