#!/usr/bin/env bash
# Chaos test: kill Redis mid-traffic and confirm Maluca degrades (per
# fail-mode) instead of crashing, then recovers when Redis returns.
#
# Expectation:
#   - fail-open routes (e.g. /api/products) keep returning 200/502 while Redis
#     is down (no shared state, but requests flow)
#   - fail-closed routes (/login) return 403 while Redis is down
#   - /actuator/health reports degradation=PASSTHROUGH, status DEGRADED
#   - after Redis restarts and the breaker half-opens, full scoring resumes
set -uo pipefail

PROXY="${PROXY:-http://localhost:8080}"

probe() {
  local path="$1"
  curl -s -o /dev/null -w "%{http_code}" "$PROXY$path"
}

echo "== before: Redis up =="
echo "  /api/products -> $(probe /api/products)"
echo "  /login        -> $(probe /login)"
echo "  health        -> $(curl -s "$PROXY/actuator/health" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("components",{}).get("redisBreaker",{}).get("details",{}))' 2>/dev/null)"

echo "== killing Redis =="
if command -v docker >/dev/null 2>&1; then
  docker stop maluca-redis >/dev/null 2>&1 || true
else
  # local Homebrew redis: kill the process
  pkill -f "redis-server" || true
fi

echo "== generating traffic against dead Redis (breaker should open) =="
for i in $(seq 1 30); do probe /api/products >/dev/null; done
sleep 1
echo "  /api/products -> $(probe /api/products)  (fail-open: should still serve)"
echo "  /login        -> $(probe /login)         (fail-closed: should be 403)"
echo "  health        -> $(curl -s "$PROXY/actuator/health" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("status="+d.get("status","?"),d.get("components",{}).get("redisBreaker",{}).get("details",{}))' 2>/dev/null)"

echo "== restarting Redis =="
if command -v docker >/dev/null 2>&1; then
  docker start maluca-redis >/dev/null 2>&1 || true
else
  redis-server --port 6379 --save '' --daemonize yes 2>/dev/null || \
    /opt/homebrew/opt/redis/bin/redis-server --port 6379 --save '' --daemonize yes 2>/dev/null || true
fi
sleep 12   # wait past open-state-seconds for the breaker to half-open
for i in $(seq 1 10); do probe /api/products >/dev/null; done
echo "  health        -> $(curl -s "$PROXY/actuator/health" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("status="+d.get("status","?"),d.get("components",{}).get("redisBreaker",{}).get("details",{}))' 2>/dev/null)"
echo "== done =="
