-- Sliding-window log: keeps every admitted request timestamp in a ZSET.
-- Exact — no boundary artifacts at all — but memory grows with the limit
-- (one ZSET entry per admitted request in the window), so reserve it for
-- low-limit, high-value endpoints like /login.
--
-- KEYS[1] = zset key  (KEYS[1]..':seq' is used for unique members)
-- ARGV[1] = limit
-- ARGV[2] = window seconds
--
-- Returns {allowed(0/1), countInWindow, retryAfterSeconds}

local limit = tonumber(ARGV[1])
local window_us = tonumber(ARGV[2]) * 1e6

local time = redis.call('TIME')
local now_us = tonumber(time[1]) * 1e6 + tonumber(time[2])

redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now_us - window_us)
local count = redis.call('ZCARD', KEYS[1])

if count < limit then
  local seq = redis.call('INCR', KEYS[1] .. ':seq')
  redis.call('ZADD', KEYS[1], now_us, now_us .. '-' .. seq)
  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]) * 2)
  redis.call('EXPIRE', KEYS[1] .. ':seq', tonumber(ARGV[2]) * 2)
  return {1, count + 1, 0}
end

local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
local retry = 1
if oldest[2] then
  retry = math.max(1, math.ceil((tonumber(oldest[2]) + window_us - now_us) / 1e6))
end
return {0, count, retry}
