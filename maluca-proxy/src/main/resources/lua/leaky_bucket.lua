-- Leaky bucket (policing variant): a virtual queue drains at a constant
-- `rate`/s; each request adds 1 to the queue if it fits within `capacity`,
-- otherwise it is rejected immediately (no queuing/shaping in this variant —
-- shaping is what SOFT_LIMIT's delay does at the mitigation layer).
-- Output rate is perfectly smooth; bursts are absorbed only up to capacity.
--
-- KEYS[1] = bucket hash {level, ts}
-- ARGV[1] = drain rate per second
-- ARGV[2] = capacity (max queue depth)
--
-- Returns {allowed(0/1), level, retryAfterSeconds}

local rate = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])

local time = redis.call('TIME')
local now = tonumber(time[1]) + tonumber(time[2]) / 1e6

local data = redis.call('HMGET', KEYS[1], 'level', 'ts')
local level = tonumber(data[1])
local ts = tonumber(data[2])
if level == nil or ts == nil then
  level = 0
  ts = now
end

level = math.max(0, level - math.max(0, now - ts) * rate)

local allowed = 0
local retry = 0
if level + 1 <= capacity then
  level = level + 1
  allowed = 1
else
  retry = math.ceil((level + 1 - capacity) / rate)
end

redis.call('HSET', KEYS[1], 'level', tostring(level), 'ts', tostring(now))
redis.call('EXPIRE', KEYS[1], math.max(math.ceil(capacity / rate) * 2, 60))

return {allowed, math.floor(level), retry}
