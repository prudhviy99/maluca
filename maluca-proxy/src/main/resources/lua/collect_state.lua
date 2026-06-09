-- Collects and updates all behavioral state for one request in a single
-- atomic round trip.
--
-- KEYS[1] = 10s counter        KEYS[2] = 60s counter
-- KEYS[3] = 5m counter         KEYS[4] = 1h counter
-- KEYS[5] = distinct-path set  KEYS[6] = sensitive-hit counter
-- KEYS[7] = sticky decision    KEYS[8] = 4xx counter (read-only here)
--
-- ARGV[1] = request path
-- ARGV[2] = '1' if the path is sensitive
-- ARGV[3] = distinct-path window TTL seconds
-- ARGV[4] = sensitive counter TTL seconds
--
-- Returns {c10, c60, c300, c3600, distinctPaths, sensitiveHits, fourxx, decision, decisionTtl}

local function bump(key, ttl)
  local v = redis.call('INCR', key)
  if v == 1 then
    redis.call('EXPIRE', key, ttl)
  end
  return v
end

local c10 = bump(KEYS[1], 10)
local c60 = bump(KEYS[2], 60)
local c300 = bump(KEYS[3], 300)
local c3600 = bump(KEYS[4], 3600)

redis.call('SADD', KEYS[5], ARGV[1])
redis.call('EXPIRE', KEYS[5], tonumber(ARGV[3]), 'NX')
local paths = redis.call('SCARD', KEYS[5])

local sens
if ARGV[2] == '1' then
  sens = bump(KEYS[6], tonumber(ARGV[4]))
else
  sens = tonumber(redis.call('GET', KEYS[6]) or '0')
end

local fourxx = tonumber(redis.call('GET', KEYS[8]) or '0')

local decision = redis.call('GET', KEYS[7]) or ''
local decision_ttl = redis.call('TTL', KEYS[7])

return {c10, c60, c300, c3600, paths, sens, fourxx, decision, decision_ttl}
