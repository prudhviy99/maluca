# Maluca

**An adaptive HTTP bot/DDoS mitigation reverse proxy** — built in Java with
Spring WebFlux and Redis. Maluca sits in front of a backend, scores every
request on a 0–100 risk scale from layered behavioral signals, and chooses
from six progressive mitigation actions via a hot-reloadable policy engine.
Its optional local-AI control plane detects incidents, retrieves cited
runbooks from PostgreSQL/pgvector, generates grounded reports with Ollama,
and exposes bounded operational tools over MCP.

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

## Run the AI incident-triage stack

Copy [`.env.example`](.env.example) to `.env`, replace every development
secret, then start the opt-in overlay:

```bash
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage up --build
```

This adds pgvector PostgreSQL, Ollama, a one-shot model pull, the triage API
on **http://localhost:8082**, the Streamable-HTTP MCP server on
**http://localhost:8083/mcp**, and Prometheus. The default first run downloads
`qwen3:14b` and `nomic-embed-text`; model data is retained in a named volume.
To use Gemma instead, set `OLLAMA_CHAT_MODEL=gemma4:e4b` in `.env` before
starting the overlay.
For NVIDIA acceleration, add `-f docker-compose.gpu.yml` before
`--profile triage`.

```bash
# inspect detected incidents
curl -H "Authorization: Bearer $TRIAGE_API_TOKEN" \
  http://localhost:8082/api/v1/incidents

# retrieve a report as Markdown
curl -H "Authorization: Bearer $TRIAGE_API_TOKEN" \
  http://localhost:8082/api/v1/incidents/INCIDENT_ID/report.md
```

Policy changes are proposals by default. Application is disabled unless both
the triage and MCP apply switches are explicitly enabled, and it requires a
separate human/operator credential plus optimistic incident and file hashes.

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
| Shared proxy/triage/MCP wire contracts | `maluca-contracts/` |
| Deterministic incident detection + lifecycle | `maluca-triage/detection/`, `incident/` |
| pgvector runbook RAG + local Ollama structured reports | `maluca-triage/runbook/`, `agent/` |
| Audited, CAS-guarded policy proposal/apply workflow | `maluca-triage/policy/` |
| Streamable-HTTP MCP operational tools | `maluca-mcp/` |
| Offline and opt-in model regression evaluations | `maluca-triage/src/test/` |

## Documentation

- [`docs/beginner-guide.md`](docs/beginner-guide.md) — complete beginner walkthrough of the proxy and AI control plane: Java/Spring concepts, HLD, LLD, RAG, Ollama, pgvector, MCP, safety boundaries, tests, and end-to-end code flow
- [`docs/triage-project-guide.md`](docs/triage-project-guide.md) — complete as-built AI triage guide, startup, APIs, flows, safety, testing, and operations
- [`docs/triage-implementation-plan.md`](docs/triage-implementation-plan.md) — completed delivery plan and acceptance boundaries
- [`docs/triage/architecture.md`](docs/triage/architecture.md) — component and runtime architecture
- [`docs/triage/configuration.md`](docs/triage/configuration.md) — environment variables, defaults, timing constraints, and deployment profiles
- [`docs/triage/data-model.md`](docs/triage/data-model.md) — PostgreSQL schema, lifecycle state, leases, hashes, and audit records
- [`docs/triage/evaluation.md`](docs/triage/evaluation.md) — deterministic, retrieval, safety, and live-Ollama evaluation strategy and results
- [`docs/triage/security.md`](docs/triage/security.md) — trust boundaries, credentials, and remediation controls
- [`maluca-triage/README.md`](maluca-triage/README.md) — triage service developer reference
- [`maluca-mcp/README.md`](maluca-mcp/README.md) — MCP clients, tool contracts, and authorization
- [`docs/algorithms.md`](docs/algorithms.md) — the five rate limiters, with trade-offs
- [`docs/benchmarks.md`](docs/benchmarks.md) — measured latency + mitigation effectiveness (and where it loses)
- [`docs/slos.md`](docs/slos.md) — SLIs/SLOs, golden signals, cardinality rules
- [`ops/RUNBOOK.md`](ops/RUNBOOK.md) — operating Maluca when things go wrong
- [`ops/TRIAGE_RUNBOOK.md`](ops/TRIAGE_RUNBOOK.md) — operating and recovering the AI incident control plane

## Build & test locally (without Docker)

Needs JDK 21 and a local Redis on :6379.

```bash
./gradlew test                       # deterministic suite; Docker-backed tests self-skip if unavailable
./gradlew :maluca-triage:llmTest     # opt-in; requires the configured Ollama models
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

The mitigation proxy and optional AI incident-triage control plane are both
implemented. The control plane includes decision export, deterministic anomaly
detection, pgvector RAG, source-cited local inference, MCP tools, guarded policy
remediation, migrations, Compose packaging, runbooks, and regression tests.
The proxy remains independently deployable because decision export is disabled
by default and never blocks request processing.
