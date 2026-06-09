-- Sliding-window counter: weights the previous fixed window by how much of
-- it still overlaps the sliding window. Smooths the fixed-window boundary
-- burst at the cost of slight approximation (assumes uniform arrival in the
-- previous window).
--
-- KEYS[1] = counter key prefix
-- ARGV[1] = limit
-- ARGV[2] = window seconds
--
-- Returns {allowed(0/1), weightedCount, retryAfterSeconds}

local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

local time = redis.call('TIME')
local now = tonumber(time[1]) + tonumber(time[2]) / 1e6
local bucket = math.floor(now / window)
local pos = (now % window) / window  -- fraction elapsed of the current window

local curr_key = KEYS[1] .. ':' .. bucket
local prev_key = KEYS[1] .. ':' .. (bucket - 1)

local curr = tonumber(redis.call('GET', curr_key) or '0')
local prev = tonumber(redis.call('GET', prev_key) or '0')
local weighted = curr + prev * (1 - pos)

if weighted + 1 <= limit then
  local v = redis.call('INCR', curr_key)
  if v == 1 then
    redis.call('EXPIRE', curr_key, window * 2)
  end
  return {1, math.floor(weighted + 1), 0}
end

-- estimate when enough of the previous window slides out
local retry
if prev > 0 then
  retry = math.ceil(window * (weighted + 1 - limit) / prev)
else
  retry = math.ceil(window - (now % window))
end
return {0, math.floor(weighted), math.max(retry, 1)}
