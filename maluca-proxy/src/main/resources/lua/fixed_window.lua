-- Fixed-window rate limiter, window-aligned using the Redis server clock
-- (never the app-server clock — avoids skew between proxy instances).
--
-- KEYS[1] = counter key prefix
-- ARGV[1] = limit
-- ARGV[2] = window seconds
--
-- Returns {allowed(0/1), current, retryAfterSeconds}

local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

local time = redis.call('TIME')
local now = tonumber(time[1])
local bucket = math.floor(now / window)
local key = KEYS[1] .. ':' .. bucket

local current = redis.call('INCR', key)
if current == 1 then
  -- keep one extra window around for debugging; the bucket suffix isolates windows
  redis.call('EXPIRE', key, window * 2)
end

if current <= limit then
  return {1, current, 0}
end

local retry_after = (bucket + 1) * window - now
return {0, current, retry_after}
