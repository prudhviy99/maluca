-- Token bucket: tokens refill continuously at `rate`/s up to `burst`.
-- Allows legitimate bursts up to bucket capacity while enforcing the average
-- rate — the classic choice for public API quotas.
--
-- KEYS[1] = bucket hash {tokens, ts}
-- ARGV[1] = refill rate per second (may be fractional)
-- ARGV[2] = burst capacity
-- ARGV[3] = tokens requested (normally 1)
--
-- Returns {allowed(0/1), tokensRemaining, retryAfterSeconds}

local rate = tonumber(ARGV[1])
local burst = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

local time = redis.call('TIME')
local now = tonumber(time[1]) + tonumber(time[2]) / 1e6

local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(data[1])
local ts = tonumber(data[2])
if tokens == nil or ts == nil then
  tokens = burst
  ts = now
end

tokens = math.min(burst, tokens + math.max(0, now - ts) * rate)

local allowed = 0
local retry = 0
if tokens >= requested then
  tokens = tokens - requested
  allowed = 1
else
  retry = math.ceil((requested - tokens) / rate)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
redis.call('EXPIRE', KEYS[1], math.max(math.ceil(burst / rate) * 2, 60))

return {allowed, math.floor(tokens), retry}
