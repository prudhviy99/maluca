# Maluca — Project Summary

This file is meant to give an implementation agent enough context to start the project, set up the environment, and build the first working version without needing to reconstruct the idea from chat history.

---

## 1. What Maluca is

**Maluca** is a **production-style adaptive HTTP bot / DDoS mitigation reverse proxy**.

It sits in front of a backend web application, inspects incoming HTTP traffic in real time, assigns a **risk score** to clients/requests, and applies **progressive mitigations** when traffic looks suspicious.

The goal is **not** to build a toy rate limiter. The goal is to build something that feels like a small but real traffic-management / edge-security system.

### Core idea

Given live HTTP requests coming into a service:
- observe request behavior
- track short-term client patterns
- classify traffic as normal vs suspicious
- progressively respond with:
  - allow
  - soft rate limit
  - hard rate limit
  - challenge
  - block

---

## 2. Why this project exists

This project is meant to do two things:

1. **Portfolio / interview value**
   - Show ability to build a real backend / infrastructure-heavy system.
   - Demonstrate knowledge relevant to traffic engineering, reverse proxies, bot mitigation, distributed systems, rate limiting, and observability.
   - Provide a project that can be discussed deeply in interviews.

2. **Learning value**
   - Learn how modern traffic/security systems are structured.
   - Understand reverse proxy flow, stateful request analysis, mitigation strategies, Redis/distributed state, and production-style metrics/logging.
   - Move from “ops / maintenance experience” toward “I designed and built a meaningful system from scratch.”

---

## 3. Original complete vision for Maluca

The **full version** of Maluca is a reverse proxy that supports:

### Traffic inspection
- Parse incoming HTTP requests.
- Extract client identity using configurable rules:
  - IP address
  - forwarded IP headers (when trusted)
  - user-agent
  - cookies/session hints
  - optional fingerprint key derived from headers
- Track request behavior over multiple rolling windows.

### Behavioral detection / scoring
For each client or fingerprint, compute suspiciousness using signals like:
- request frequency / burstiness
- repeated hits to expensive or sensitive paths
- high path variance or path scanning behavior
- header entropy / malformed headers / missing common browser headers
- abnormal user-agent usage
- very low inter-request think time
- repeated 4xx / 403 / 404 patterns
- concurrency spikes
- challenge failure history

These signals feed a **risk score engine**.

### Progressive mitigation pipeline
Depending on the score and policy:
- **ALLOW** — pass through normally
- **OBSERVE** — allow but log more aggressively
- **SOFT_RATE_LIMIT** — slow or throttle the client
- **HARD_RATE_LIMIT** — return 429
- **CHALLENGE** — return challenge page / JS challenge / proof-of-work placeholder / CAPTCHA simulation
- **BLOCK** — return 403 or drop early

Mitigation should not be binary only. The whole point is progressive defense.

### State and horizontal scale
The full version should support distributed state, ideally with:
- **Redis** as the primary shared state store
- local in-memory caches for hot counters or short-lived fast path decisions
- TTL-based state eviction
- sliding-window / token-bucket counters that work across instances

### Policy engine
Maluca should have configurable policies by:
- route or route pattern
- client type
- environment
- detection thresholds
- mitigation action

Example:
- `/login` gets stricter thresholds than `/health`
- `/search` may tolerate higher QPS than `/checkout`
- internal or allowlisted clients bypass some checks

### Observability
The full version should expose:
- request counts
- allowed vs challenged vs rate-limited vs blocked counts
- top suspicious clients
- p50 / p95 / p99 added latency
- upstream response codes
- mitigation decision breakdown
- false-positive review signals if possible

Use:
- structured logs
- metrics endpoint (Prometheus style preferred)
- dashboards later if desired

### Attack / load simulation
The complete project should also include tools or scripts to simulate:
- normal browser traffic
- bursty bot traffic
- path scanning
- credential stuffing style login bursts
- header-randomizing bot traffic

This is important because the project needs a way to demonstrate that mitigations actually trigger.

### Benchmarking / evaluation
Compare Maluca against a plain backend or simple NGINX rate limit setup on:
- request throughput
- added latency
- mitigation accuracy / usefulness
- behavior under attack traffic

### Production-style packaging
Full project should eventually include:
- Dockerized local environment
- backend demo app behind the proxy
- Redis container
- repeatable local dev startup
- clean README
- configuration-driven policies
- test coverage for core logic

---

## 4. One-week version we want first

The **one-week goal is not the full system**.
It is a **strong MVP** that proves the architecture and gives us something real to run, demo, and iterate on.

### One-week success criteria
By the end of the week, Maluca should:
- run locally with Docker Compose
- accept HTTP traffic as a reverse proxy
- forward normal traffic to a demo backend
- track per-client request rates and simple behavior signals
- assign a risk score
- apply at least 3 actions:
  - allow
  - rate limit (429)
  - block/challenge placeholder
- store distributed counters/state in Redis
- expose basic metrics
- emit structured logs for decisions
- include a traffic generator script to simulate normal vs suspicious clients

That is enough to say:
> “I built an adaptive reverse proxy with real-time scoring, Redis-backed rate/state tracking, and progressive mitigations.”

---

## 5. Scope for the one-week MVP

### In scope

#### A. Reverse proxy core
- HTTP reverse proxy in Java
- Receive inbound requests
- Forward allowed requests to upstream backend
- Preserve method, path, headers, and body where reasonable
- Return upstream response to client

#### B. Client identification
Start simple:
- client key = IP by default
- optionally trust `X-Forwarded-For` when enabled in config

#### C. Basic behavioral signals
Implement a small number of useful signals first:
- requests per 10 seconds
- requests per 60 seconds
- distinct path count in short window
- repeated hits to sensitive endpoints (`/login`, `/admin`, `/api/*`)
- recent 4xx-heavy behavior (optional if easy)

#### D. Scoring
Simple weighted scoring is enough initially.
Example concept:
- high short-window QPS: +40
- very high 60-second count: +25
- many distinct paths in short window: +20
- sensitive endpoint burst: +25
- known blocked client / prior failures: +50

Risk bands:
- 0–39: allow
- 40–69: observe / maybe soft limit
- 70–89: rate limit
- 90+: block or challenge placeholder

Exact values do not matter initially. Keep them config-driven where possible.

#### E. Mitigation actions
For week 1, support:
- **ALLOW** → proxy upstream
- **RATE_LIMIT** → return HTTP 429 with JSON response
- **BLOCK** → return HTTP 403 with JSON response

Optional:
- **CHALLENGE** placeholder endpoint/page, but do not overbuild real CAPTCHA/JS logic yet

#### F. Shared state with Redis
Store:
- request counters by client and time window
- temporary decisions / block TTLs
- maybe a few rolling sets for distinct path counting

Use TTL heavily so state expires automatically.

#### G. Observability
Minimum observability:
- structured JSON logs per request decision
- metrics endpoint with counters:
  - total requests
  - proxied requests
  - rate-limited requests
  - blocked requests
  - errors
  - upstream latency

#### H. Demo environment
Docker Compose with:
- `maluca-proxy`
- `demo-backend`
- `redis`

#### I. Traffic simulation
A simple script that can generate:
- normal steady traffic
- burst traffic
- path scan traffic
- login brute-force style traffic

Python script is fine.

---

## 6. Explicitly out of scope for week 1

Do **not** spend week 1 building these unless the MVP is already done:
- full browser fingerprinting
- real CAPTCHA integration
- production-grade JS challenge
- advanced ML/anomaly models
- multi-region deployment
- Kubernetes
- DynamoDB fallback store
- polished UI/dashboard
- WAF rule language
- advanced bot identity correlation
- perfect mitigation accuracy

These belong to later phases.

---

## 7. Recommended tech stack

### Main implementation
- **Language:** Java
- **Framework/runtime:** prefer **Spring Boot WebFlux** or **Netty-based implementation**

### Recommendation
For week 1, prefer:
- **Spring Boot + WebFlux**

Reason:
- faster to build than raw Netty
- still relevant for non-blocking proxy / reactive service design
- easier metrics/config/bootstrap
- quicker for one-developer execution

Netty can still be used later if deeper control is needed.

### Other components
- **Redis** for shared counters/state
- **Docker Compose** for local environment
- **Micrometer + Prometheus endpoint** for metrics
- **Python** for traffic simulation scripts

---

## 8. Proposed project structure

```text
maluca/
  README.md
  MALUCA_PROJECT_SUMMARY.md
  docker-compose.yml
  .env.example

  maluca-proxy/
    build.gradle or pom.xml
    src/main/java/.../
      config/
      proxy/
      scoring/
      mitigation/
      state/
      metrics/
      model/
      util/
    src/main/resources/
      application.yml

  demo-backend/
    simple HTTP app

  scripts/
    traffic/
      normal_load.py
      burst_attack.py
      scan_attack.py

  docs/
    architecture.md
    roadmap.md
```

---

## 9. Recommended internal modules for Maluca

### `proxy`
Responsible for:
- receiving incoming HTTP requests
- invoking decision/scoring pipeline
- forwarding allowed traffic upstream
- returning synthetic responses for blocked/rate-limited traffic

### `state`
Responsible for:
- Redis access
- counters
- TTL management
- distinct path tracking
- temporary blocks

### `scoring`
Responsible for:
- computing signals from request + state
- assigning weighted score
- producing decision context

### `mitigation`
Responsible for:
- mapping score/policy to action
- generating 429/403/challenge responses

### `config`
Responsible for:
- policy thresholds
- trusted proxy config
- sensitive paths
- upstream target
- Redis connection

### `metrics`
Responsible for:
- counters/timers
- decision metrics
- latency metrics

### `model`
Contains DTOs / domain objects like:
- `RequestContext`
- `ClientProfile`
- `RiskSignals`
- `RiskDecision`
- `MitigationAction`

---

## 10. Core request flow

1. Request enters Maluca.
2. Extract client identity.
3. Read/update Redis-backed counters for the client.
4. Compute signals.
5. Compute risk score.
6. Map score to action.
7. If action is allow:
   - proxy request to upstream
   - return upstream response
8. If action is rate limit/block/challenge:
   - return synthetic response immediately
9. Emit metrics and structured logs.

---

## 11. Suggested data model / state ideas

These do not need to be perfect. They just need to be practical.

### Redis keys (example)
- `client:{key}:count:10s`
- `client:{key}:count:60s`
- `client:{key}:paths:10s`
- `client:{key}:blocked`
- `client:{key}:last_seen`
- `client:{key}:sensitive_hits:60s`

### State behavior
- increment counters with TTL
- use sets/hyperloglog/simple sets for distinct path count if easy
- block entries should expire automatically after TTL

---

## 12. Example policy for MVP

### Default thresholds
- > 30 requests / 10s → suspicious
- > 120 requests / 60s → more suspicious
- > 15 distinct paths / 30s → likely scanning
- > 20 hits to `/login` in 60s → likely brute force

### Action mapping
- score < 40 → allow
- 40–69 → allow + log as suspicious
- 70–89 → rate limit
- 90+ → block for 5 minutes

This should be easy to tune.

---

## 13. Deliverables for week 1

By the end of the week, the repo should contain:

1. **Working reverse proxy service**
2. **Demo backend service**
3. **Redis-backed state/counters**
4. **Scoring + mitigation pipeline**
5. **Metrics endpoint**
6. **Structured logs**
7. **Docker Compose local stack**
8. **Traffic simulation scripts**
9. **README with run instructions**
10. **At least basic tests for scoring / policy mapping**

---

## 14. Practical one-week build plan

### Day 1 — bootstrap and environment
- initialize repo
- create Spring Boot/WebFlux service
- create simple demo backend
- create Docker Compose with Redis + backend + proxy
- verify proxy service can start and connect to Redis

### Day 2 — reverse proxy path
- implement request receive + upstream forwarding
- make sure allow path works end-to-end
- preserve status/body/headers well enough for demo

### Day 3 — Redis counters + client identity
- extract client key
- implement per-window counters in Redis
- add TTL-based state
- log counters per request for debugging

### Day 4 — scoring engine
- compute simple signals
- add weighted score
- define action mapping
- add config for thresholds

### Day 5 — mitigation responses + metrics
- implement 429 and 403 responses
- add structured logs
- add metrics endpoint
- instrument upstream latency and decisions

### Day 6 — traffic simulation + tuning
- write scripts for normal and attack traffic
- tune thresholds so behavior is easy to demonstrate
- validate rate limiting/blocking works

### Day 7 — cleanup and documentation
- add README
- add architecture notes
- add screenshots/log examples if useful
- add basic tests
- make local setup reproducible from scratch

---

## 15. What “done” means for the MVP

The MVP is done when a new developer can:
- clone the repo
- run Docker Compose
- send normal traffic through Maluca and see it proxied
- run attack scripts and see Maluca start rate-limiting or blocking
- inspect logs/metrics and understand why decisions were made

If that works, the project is already valuable.

---

## 16. Roadmap after week 1

Once the MVP is stable, the next upgrades should be:

### Phase 2
- challenge action/page
- better path-pattern policies
- sliding window refinement
- better client fingerprinting
- allowlists / denylists
- richer dashboarding

### Phase 3
- advanced heuristics
- reputation memory / decay
- adaptive thresholding
- benchmark suite
- NGINX comparison writeup
- deployment to EC2

### Phase 4
- more production-hardening
- distributed multi-instance test
- DynamoDB or alternate persistence experiments
- more realistic attack simulations

---

## 17. Environment setup expectations for Claude Code / implementation agent

If an agent is reading this file to get started, the first tasks should be:

1. Create the repo/module structure.
2. Choose build tool:
   - Gradle preferred, Maven acceptable.
3. Create a Spring Boot WebFlux app called `maluca-proxy`.
4. Create a minimal demo backend service.
5. Add Redis dependency and connectivity.
6. Add Docker Compose for proxy + backend + redis.
7. Implement the simplest pass-through reverse proxy first.
8. Then layer scoring and mitigations incrementally.

### Important implementation guidance
- Prioritize working end-to-end behavior over elegance.
- Keep policy and thresholds configurable.
- Keep logs readable and structured.
- Do not prematurely over-engineer abstractions.
- Ship MVP first, then refine.

---

## 18. Non-goals / anti-patterns

Avoid spending early time on:
- unnecessary generic frameworks
- complex plugin systems
- advanced UI before core proxy works
- perfect distributed correctness before MVP exists
- broad security claims that the project cannot honestly support

Maluca should be framed as:
- a serious adaptive mitigation proxy prototype
- not a production-ready enterprise WAF replacement

---

## 19. Resume / interview framing

Once MVP exists, the project can be described roughly like this:

> Built **Maluca**, an adaptive HTTP reverse proxy for bot and DDoS mitigation that performs real-time behavioral scoring, Redis-backed distributed rate/state tracking, and progressive mitigations such as allow, rate-limit, and block, with metrics/logging for observability and attack simulation for validation.

Deeper talking points:
- Why reverse proxy instead of app middleware
- Why Redis for shared counters/state
- How scoring signals were chosen
- Tradeoffs between false positives and fast mitigation
- Performance overhead of inline inspection
- Challenges around client identity and trusted forwarding headers
- How you would scale it horizontally

---

## 20. Final instruction to implementation agent

Build the **one-week MVP first**.
Do **not** try to implement the full final vision immediately.

Priority order:
1. working proxy
2. Redis-backed counters
3. scoring
4. mitigation actions
5. metrics/logging
6. traffic simulation
7. cleanup/documentation

A simple, working, demonstrable system is better than a half-built ambitious architecture.

