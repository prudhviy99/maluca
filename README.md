# Maluca

**An adaptive HTTP bot/DDoS mitigation reverse proxy** — built in Java with
Spring WebFlux and Redis. Maluca sits in front of a backend, scores every
request on a 0–100 risk scale from layered behavioral signals, and chooses
from six progressive mitigation actions via a hot-reloadable policy engine.

> Maluca scores every incoming request on a 0–100 risk scale using layered
> behavioral signals, chooses from six progressive mitigation actions
> (allow → observe → soft-limit → 429 → proof-of-work challenge → block) via
> a hot-reloadable per-route policy engine, and has a measured p99 added
> latency of ~5ms with a circuit-breaker-guarded Redis backend and a
> Testcontainers-backed test suite.

## Run it

Requires Docker. One command, clone-to-running:

```bash
docker compose up
```

That starts Redis, a demo backend, and Maluca on **http://localhost:8080**.
Then:

```bash
# normal request — proxied to the backend
curl http://localhost:8080/api/products

# burst it — watch the slide into 429 then 403
for i in $(seq 1 80); do curl -s -o /dev/null -w "%{http_code} " http://localhost:8080/api/products; done; echo

# metrics + health
curl http://localhost:8080/actuator/prometheus | grep maluca_
curl http://localhost:8080/actuator/health
```

Full observability stack (Prometheus + Grafana + Jaeger):

```bash
docker compose --profile observability up
# Grafana  http://localhost:3000  (dashboard: "Maluca — Mitigation Proxy")
# Prometheus http://localhost:9090
# Jaeger   http://localhost:16686
```

Multi-instance (two proxies behind NGINX, shared Redis):

```bash
docker compose -f docker-compose.yml -f docker-compose.multi.yml \
  up redis demo-backend maluca-proxy-1 maluca-proxy-2 lb
```

## Try the attack simulators

```bash
python3 scripts/traffic/normal.py    --duration 20    # browser traffic, left alone
python3 scripts/traffic/burst.py     --duration 10    # flood -> mitigated
python3 scripts/traffic/scan.py      --duration 10    # path enumeration
python3 scripts/traffic/credstuff.py --duration 10    # /login brute force
python3 scripts/traffic/lowslow.py   --duration 20    # 50 IPs @ 1rps each
python3 scripts/bench/latency_bench.py --rps 200 --duration 20   # coordinated-omission-corrected latency
```

## How a request flows

```
client → Maluca ──────────────────────────────────────────────→ backend
            │
            ├─ tier (API key) → resolve per-route policy
            ├─ identity: IP / trusted-XFF, + session + passive fingerprint
            ├─ pass-cookie? → bypass.   allow/denylist? → short-circuit.
            ├─ collect behavioral state  ← 1 atomic Redis Lua round trip
            ├─ rate limit (policy's algorithm, Lua, atomic across instances)
            ├─ signals → weighted-linear score (0–100, every term logged)
            ├─ score → policy bands → action, + hysteresis floor
            └─ execute: proxy │ delay │ 429 │ challenge │ 403
```

Every Redis call is wrapped in a circuit breaker with a 10ms timeout; if
Redis fails, each route degrades per its `fail-open`/`fail-closed` policy
instead of erroring.

## What's inside

| Capability | Where |
|---|---|
| Streaming reverse proxy (constant-memory bodies, hop-by-hop stripping) | `proxy/` |
| Layered identity: IP, trusted XFF, session, passive fingerprint, FCrDNS verified-bot check | `identity/` |
| Rolling-window behavioral state (10s/60s/5m/1h) in one atomic Lua script | `state/`, `resources/lua/collect_state.lua` |
| Five rate-limit algorithms (fixed/sliding-counter/sliding-log/token/leaky bucket) | `ratelimit/`, `resources/lua/` |
| Weighted-linear risk scorer with per-signal explainability | `scoring/` |
| Progressive mitigation + hysteresis | `mitigation/` |
| Proof-of-work (SHA-256 hashcash) + JS-lite challenges, HMAC pass cookies | `challenge/` |
| Hot-reloadable per-route policy engine + admin API | `policy/` |
| Metrics, OpenTelemetry traces, structured decision logs | `metrics/`, `web/DecisionLogger` |
| Redis circuit breaker + graceful degradation tiers | `state/RedisCircuitBreaker` |

## Documentation

- [`docs/algorithms.md`](docs/algorithms.md) — the five rate limiters, with trade-offs
- [`docs/benchmarks.md`](docs/benchmarks.md) — measured latency + mitigation effectiveness (and where it loses)
- [`docs/slos.md`](docs/slos.md) — SLIs/SLOs, golden signals, cardinality rules
- [`ops/RUNBOOK.md`](ops/RUNBOOK.md) — operating Maluca when things go wrong

## Build & test locally (without Docker)

Needs JDK 21 and a local Redis on :6379.

```bash
./gradlew test                       # 77 tests; Redis-backed + Testcontainers tests self-skip if unavailable
./gradlew :demo-backend:bootRun &    # backend on :8081
./gradlew :maluca-proxy:bootRun      # proxy on :8080
```

## Configuration

Infrastructure config (restart required) lives in
`maluca-proxy/src/main/resources/application.yml` and env vars (see
[`.env.example`](.env.example)). Behavioral policy (hot-reloadable) lives in
`config/policies.yml` when `POLICY_FILE` points at it. Secrets
(`MALUCA_CHALLENGE_SECRET`, `MALUCA_ADMIN_TOKEN`) come from the environment —
never commit them; a pre-commit hook in `scripts/git-hooks/` blocks accidents.

## Status

Phases 0–10 of the master plan are implemented: proxy core, scoring,
five rate limiters, challenges, composite identity, policy engine,
observability, attack/benchmark harness, resilience, and production
packaging. The ML/behavioral expansions (Phase F) are future work.
