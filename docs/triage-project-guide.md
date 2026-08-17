# Maluca AI Incident Triage: Complete Project Guide

This is the top-level as-built guide for the AI incident triage project added
to Maluca. It records what exists, how the parts interact, how to run and test
them, and which guarantees are enforced by code. The pre-implementation design
is retained in [`triage-spec.md`](triage-spec.md), and the implementation plan
is retained in [`triage-implementation-plan.md`](triage-implementation-plan.md).

## What was implemented

The repository now contains a complete, optional control plane around the
existing WebFlux mitigation proxy:

- a shared Java contracts module;
- a bounded, lossy, asynchronous proxy decision exporter;
- PostgreSQL/Flyway storage with pgvector and HNSW runbook search;
- deterministic anomaly detection and an auditable incident lifecycle;
- local Ollama chat and embedding integration through Spring AI;
- source-cited retrieval-augmented triage with strict structured output;
- validation and fallback behavior for hallucinated evidence or citations;
- seven reviewed operational runbooks with stable H2 chunks and fail-closed,
  transactionally replaced embeddings;
- a standalone Streamable-HTTP MCP server with bounded operational tools;
- proposal-only model remediation plus a separately authorized human apply
  workflow with compare-and-swap, atomic replacement, reload verification,
  audit records, backups, and rollback;
- deterministic, Testcontainers, MCP integration, and opt-in Ollama regression
  suites;
- pinned Docker images, CPU and NVIDIA Compose variants, Prometheus scrape
  targets/alerts, CI checks, and operator runbooks.

The proxy remains independently deployable. Its decision sink is off by
default, its queue is bounded, and no PostgreSQL, Ollama, MCP, or triage call is
ever made synchronously from the request decision path.

## Repository map

| Path | Purpose |
|---|---|
| `maluca-proxy/` | Existing mitigation proxy plus decision export and structured policy admin projection |
| `maluca-contracts/` | Dependency-light records/enums shared by proxy, triage, and MCP |
| `maluca-triage/` | Database owner, detector, RAG pipeline, local agent, report API, and remediation workflow |
| `maluca-mcp/` | Standalone Spring AI MCP façade over triage, proxy admin, and Prometheus |
| `docs/runbooks/` | Trusted runbook corpus for seven incident classes |
| `docs/triage/` | Detailed as-built architecture, data, security, configuration, and evaluation references |
| `ops/TRIAGE_RUNBOOK.md` | Operational recovery and troubleshooting procedures |
| `docker-compose.triage.yml` | Opt-in CPU-compatible AI stack overlay |
| `docker-compose.gpu.yml` | Optional NVIDIA device overlay for Ollama |

## End-to-end architecture

```text
client
  |
  v
maluca-proxy :8080 ------------------------> protected backend
  |
  | non-blocking offer; bounded/drop-oldest queue
  | asynchronous HTTP batches + internal token
  v
maluca-triage :8082 -----------------------> PostgreSQL 16 + pgvector
  |        |                                      |
  |        +-- deterministic detector             +-- decisions/incidents/reports
  |        +-- incident claim worker               +-- proposals/audit
  |        +-- citation/evidence validation        +-- runbook vectors
  |
  +------------------------------------------> Ollama :11434
  |                                             qwen3:14b
  |                                             nomic-embed-text
  |
  +-- Streamable-HTTP MCP client ------------> maluca-mcp :8083/mcp
                                                  |       |       |
                                                  v       v       v
                                                triage  proxy  Prometheus
```

Request mitigation is the data plane. Everything below the asynchronous
decision offer is a control plane. If the control plane is slow or unavailable,
the proxy drops old evidence and continues serving according to its existing
Redis resilience policy.

## Runtime sequence

### 1. Decision capture and ingestion

After a proxy decision completes, `DecisionEventFactory` records a UUID,
timestamp, client key, request method/path, policy identity/mode/tier, computed
and executed actions, score, reason, contribution map, dry-run flag, and trace
ID. OBSERVE and DRY_RUN keep the computed action but execute `ALLOW`, including
denylist and Redis fail-closed early branches.

`DecisionSink.offer` never performs network I/O and never waits for capacity.
A virtual-thread worker sends batches of up to 500. It retries a failed batch
with capped exponential backoff; producers continue filling the bounded queue,
whose overflow policy discards the oldest waiting event.

Triage accepts only an internal service token at the ingest endpoint. It caps
batches and fields, allowlists action/mode values, rejects non-finite
contributions, truncates only the configured path field, HMAC-pseudonymizes
client identifiers, and inserts with `ON CONFLICT (event_id) DO NOTHING`.

### 2. Deterministic detection

Every 15 seconds by default, a transaction-scoped PostgreSQL advisory lock
allows one detector replica to evaluate the window. SQL aggregates the current
60 seconds and a preceding 15-minute baseline by policy. Java rules have stable
precedence:

1. Redis degradation from either decision reasons or the bounded Prometheus
   query `increase(maluca_redis_errors_total[60s])`;
2. challenge/block surge;
3. mitigation-share spike with absolute floors and a baseline multiplier;
4. aggregate traffic-volume surge normalized to the current-window duration.

The language model does not open incidents. A partial unique index permits at
most one active incident per policy. The opening `IncidentStats` JSON is frozen;
later anomalous polls update only `last_active_at`, without invalidating an
operator's optimistic incident version or rewriting the evidence snapshot.
Inactive stable states are resolved after the configured quiet period.

### 3. Runbook ingestion and retrieval

Each Markdown runbook has exactly five H2 sections: Symptoms, Confirm,
Remediate, False-positive checks, and Rollback. `RunbookChunker` creates one
chunk per section with a stable ID and SHA-256. On startup, ingestion reads the
packaged corpus under a transaction-scoped advisory lock, refuses to start with
an empty corpus, compares both checksums and embedding-model identity with
metadata already stored in pgvector, embeds changed chunks, and commits new
vectors plus obsolete-vector deletion atomically.

Retrieval is fail-closed behind a dedicated readiness health contributor. A
transient Ollama/network error retains readiness only when PostgreSQL still
contains a complete last-good corpus with the configured embedding-model
identity, trusted marker, checksum, bounded metadata, and bounded content; a
fresh or malformed database remains `OUT_OF_SERVICE` and the worker does not
consume incident attempts. Empty/malformed runbooks, dimension mismatches, and
other permanent ingestion errors fail startup.

Spring AI's `PgVectorStore` owns vector reads/writes. The schema is created by
Flyway, not auto-initialization: `vector(768)`, cosine distance, and an HNSW
index. Search is capped at 12 results and defaults to six with a 0.45 similarity
threshold. Every result includes its exact chunk ID, source, heading, content,
and score.

### 4. Local model triage

The worker claims one eligible `OPEN` incident atomically with
`FOR UPDATE SKIP LOCKED`,
loads at most 50 decision samples from the frozen window, and serializes the
brief inside an explicit untrusted-evidence delimiter. It retrieves runbooks,
then calls the configured local Ollama model through Spring AI `ChatClient`.
Model thinking output is explicitly disabled so production and evaluation feed
only the JSON response into the strict converter. Each attempt has an overall
orchestration deadline, a bounded model HTTP timeout, and a maximum tool-call
count; its lease must exceed the complete orchestration deadline.
Claims carry a fencing token and expiry. Failed or invalid generations return
to bounded exponential backoff, stale claims are reclaimed, and the third
failed attempt becomes `TRIAGE_FAILED` for explicit operator retry. The model
brief is capped at 16 KiB, with bounded and sanitized per-decision fields.

The model must return the `TriageResult` JSON contract: classification,
confidence, bounded summary, concrete evidence references, exact citations,
and an optional typed route-scoped patch. Its MCP callbacks are filtered by a
six-name, read-only exact allowlist. Neither external proposal creation nor
human apply is callable from the autonomous model turn.

The Java validation gate enforces:

- required classification/confidence/summary and output-size limits;
- every evidence `(fact, value)` pair must occur together in the frozen
  incident brief, preventing an unrelated field from laundering a copied
  value;
- non-UNKNOWN reports need evidence and at least one citation;
- UNKNOWN reports must be LOW confidence and cannot contain a policy patch;
- every citation ID must be in the retrieved set, with exactly matching source
  and heading, and no duplicate citation IDs;
- any patch must match the incident policy and route and satisfy the semantic
  patch validator.

One bounded repair prompt gives the model the validation errors. If it still
fails, the attempt stores an `UNKNOWN`, `LOW`, `valid=false` diagnostic report
with errors and raw response retained, but does not falsely complete the
incident; the retry/backoff budget still applies. Every report also persists
the precise retrieved chunks and similarity scores used for that inference.

### 5. Proposal and human application

Only a current, gate-valid report on a `TRIAGED` incident can produce a typed
proposal. A proposal contains supported policy fields only; it
cannot contain arbitrary YAML, commands, SQL, or filesystem paths. Proposal
creation validates the patch against the live policy, reads the current policy
file SHA-256, stores the delta and actor, and writes an audit event. A validated
model patch is stored in the same fenced database transaction as its report;
an external MCP/operator proposal is accepted only after triage. Every pending
proposal is bound to the exact `(report_id, report_created_at)` generation. A
later report generation quarantines older pending rows as
`REJECTED_STALE_PROVENANCE`. Proposal creation never modifies the policy.

Proposal creation also stores a database-derived canonical SHA-256 of the exact
JSON patch. Apply requires all of the following:

- `TRIAGE_POLICY_APPLY_ENABLED=true`;
- an authenticated triage `ROLE_OPERATOR` bearer, never the internal service
  credential;
- incident state `TRIAGED`;
- the exact reviewed proposal UUID and proposal SHA-256;
- the reviewed incident version;
- the reviewed policy SHA-256;
- a still-valid patch;
- the cluster-wide PostgreSQL policy-apply advisory lock;
- a writable policy file and parent directory.

The lock is held on one pinned PostgreSQL connection across the complete
mutation so two triage replicas cannot race the shared file. The proposal is
approved using a compare-and-set on its exact ID and digests, then the service
creates a
backup, mutates the parsed YAML, validates it, writes a same-directory temporary
file, requires an atomic filesystem move, calls the proxy reload endpoint, and
compares every proposed compiled value with the proxy's active projection. Any
failure attempts file restoration and another reload. A verified rollback is
recorded as `APPLY_FAILED`; an uncertain external/rollback state is recorded as
`APPLY_INDETERMINATE` instead of inviting a blind retry. A scheduled reconciler
repairs stranded `APPROVED` proposals only when the active file matches either
the recorded baseline or exact target digest.

Two operator-only lifecycle controls cover terminal manual states. A
`TRIAGE_FAILED` incident can be dismissed only with its exact reviewed version
and a bounded reason, producing an audit event. An `APPLY_INDETERMINATE`
proposal can be reconciled only with every recorded digest and the reviewed
incident version; the service derives the outcome from live file/proxy state.
A verified target becomes `APPLIED`, a verified baseline becomes
`APPLY_FAILED`, and any third digest stays fenced with a refused-reconciliation
audit event.

For MCP-mediated application, a second switch and two more credentials are
required. `MCP_APPLY_TOKEN` authorizes the human MCP caller;
`TRIAGE_API_TOKEN` is used only by MCP's dedicated outbound apply client. Normal
MCP read/propose calls use `X-Maluca-Internal-Token`. Startup fails if configured
credentials that cross these trust boundaries are reused.

## MCP tools

The MCP server uses Spring AI's synchronous Streamable-HTTP transport. Its
agent endpoint at `/mcp` contains exactly these seven tools:

| Tool | Operation |
|---|---|
| `get_incidents` | Bounded incident list with optional lifecycle filter |
| `get_decisions` | Bounded decisions filtered by policy, pseudonymous client, computed action, and UTC range |
| `get_signal_breakdown` | Aggregate contribution totals for one policy and bounded time range |
| `query_metrics` | Restricted Prometheus range query with metric-prefix, duration, step, sample, series, timeout, and response-size caps |
| `list_policies` | Active compiled proxy policy projection |
| `search_runbooks` | Bounded source-bearing pgvector results |
| `propose_policy_patch` | Validated proposal persistence; never approval/application |

When explicitly enabled, `approve_and_apply` is published on a physically
separate `/operator/mcp` server, whose discovery response contains only that
tool, and is guarded with `@PreAuthorize("hasRole('OPERATOR')")`. A real MCP
integration test proves the agent endpoint cannot discover the tool, an agent
bearer cannot invoke the operator endpoint, and denial causes no upstream call.

## Start the complete stack

Prerequisites are Docker Compose and enough storage/RAM for the models. Copy the
template and replace every development token:

```bash
cp .env.example .env
${EDITOR:-vi} .env

docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage up --build
```

The overlay starts PostgreSQL/pgvector, Ollama, one-shot chat/embedding model
pulls, Prometheus, proxy, demo backend, MCP, and triage. Persistent model and
database data use named volumes. The first pull of `qwen3:14b` is large.

For NVIDIA acceleration:

```bash
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  -f docker-compose.gpu.yml --profile triage up --build
```

Confirm GPU placement with `docker exec maluca-ollama ollama ps`. CPU mode is a
valid functional configuration but a 14B model may respond slowly.

Service endpoints:

| Service | Endpoint |
|---|---|
| Proxy | `http://localhost:8080` |
| Triage API | `http://localhost:8082` |
| MCP | `http://localhost:8083/mcp` |
| Operator MCP (opt-in) | `http://localhost:8083/operator/mcp` |
| Ollama | `http://localhost:11434` |
| Prometheus | `http://localhost:9090` |

Generate evidence and inspect incidents:

```bash
python3 scripts/traffic/burst.py --duration 10

curl -H "Authorization: Bearer $TRIAGE_API_TOKEN" \
  'http://localhost:8082/api/v1/incidents?limit=20'
```

Retrieve JSON and Markdown reports after the worker completes:

```bash
curl -H "Authorization: Bearer $TRIAGE_API_TOKEN" \
  "http://localhost:8082/api/v1/incidents/$INCIDENT_ID/report"

curl -H "Authorization: Bearer $TRIAGE_API_TOKEN" \
  "http://localhost:8082/api/v1/incidents/$INCIDENT_ID/report.md"
```

The service-specific API, configuration, proposal, and apply examples live in
[`../maluca-triage/README.md`](../maluca-triage/README.md). MCP client examples
and every tool input bound live in
[`../maluca-mcp/README.md`](../maluca-mcp/README.md).

## Test and evaluation strategy

Ordinary tests never require Ollama:

```bash
./gradlew clean check bootJar --no-daemon
```

The deterministic suites cover proxy decision construction/export behavior,
OBSERVE/DRY_RUN suppression on early branches, detector rule precedence,
sanitized idempotent ingestion, runbook structure/retrieval routing, grounding
and citation validation, policy validation/apply/rollback, the real pgvector
Flyway migration, MCP tool contracts, bounded upstream clients, credential
separation, and a real Streamable-HTTP authorization session.

The local/nightly model evaluation is intentionally opt-in:

```bash
OLLAMA_BASE_URL=http://localhost:11434 \
OLLAMA_CHAT_MODEL=gemma4:e4b \
./gradlew :maluca-triage:llmTest
```

It runs seven labeled fixtures for multiple repetitions at temperature zero,
a fixed seed, bounded context/time settings, and thinking disabled. A run
passes only when the result is structurally valid, classification matches, the
expected runbook citation appears, and the fixture's required/forbidden scoped
remediation expectation is satisfied; aggregate pass rate must meet the
committed baseline. This is a real Ollama test, but the current fixtures are
curated/frozen briefs and the deterministic retrieval test uses marker vectors,
not committed `nomic-embed-text` vectors. These limitations are documented so
the suite's claims stay honest.

## Verification performed for this implementation

The completed repository was verified with:

- `./gradlew clean check --no-daemon` — successful;
- proxy suite: 98 passed, including the Redis-backed rate-limiter and challenge
  cases, zero skips or failures;
- triage suite: 105 passed, including the real Testcontainers pgvector/Flyway
  migration, zero failures;
- MCP suite: 40 passed, zero failures;
- total: 243 deterministic tests passed, zero skips, failures, or errors (the
  25 Redis-dependent proxy cases were rerun against an isolated Redis 7.2
  container after the project-wide check);
- base + triage and base + triage + GPU Compose configuration validation;
- successful builds of all four application images;
- a final isolated image smoke test: MCP started with exactly seven tools and
  returned `401` to an unauthenticated `/mcp` request; triage migrated a fresh
  pgvector 0.8.5 database through Flyway schema v5, reported database health
  `UP`, and correctly reported runbook readiness `OUT_OF_SERVICE` when startup
  ingestion was intentionally disabled on an empty corpus;
- an earlier full no-Ollama container acceptance run during implementation:
  600 real proxy decisions reached PostgreSQL, the deterministic detector
  opened an incident, and MCP health returned `UP`.

The final reproducible decision-export isolation acceptance run used the built
proxy at 100 requests/second with a discarded five-second warm-up and a
ten-second measured interval. The sink-disabled baseline returned 1,000/1,000
HTTP 200 responses at 1.50 ms p99. With the sink enabled and its triage target
deliberately unreachable, another 1,000/1,000 responses were HTTP 200 at 1.30
ms p99 (a -0.20 ms delta), while `maluca_sink_failure_total` reached 183. CI
now repeats this experiment with absolute/delta p99 bounds, zero transport/5xx
allowance, and an assertion that sink failures really occurred. This small
local run validates failure isolation, not a production capacity limit.

The opt-in `llmTest` was subsequently executed on 2026-08-13 against the local
`gemma4:e4b` model. Prompt version `v4` passed 12 of 14 evaluations (seven
frozen fixtures repeated twice), for a measured pass rate of 0.857 against the
committed 0.700 minimum. The scored uncached run took approximately 56 seconds.
All 14 runs produced the expected classification and citation. The two scored
misses were credential-stuffing proposals with an algorithm-incompatible
optional limiter field; the safety finalizer discarded those patches and kept
valid diagnosis-only reports. This is a local result for that exact model tag
and runtime, not a universal model quality claim.

A fresh live-path smoke test also used local `nomic-embed-text`, a temporary
pgvector PostgreSQL 16 database, and `gemma4:e4b`. Flyway applied schema v5,
all 35 trusted chunks were embedded, semantic retrieval ranked the burst
runbook first, and a synthetic burst incident produced a persisted, valid
`BURST_FLOOD`/`MEDIUM` report with four grounded evidence items, source-matched
citations, and no unsafe policy patch.

## Operations and observability

The proxy exports queue size, drop, delivery-success, and delivery-failure
metrics. Triage exports accepted/duplicate ingestion counters, opened/resolved
incident counters, and valid/fallback report counters. Prometheus scrapes proxy,
triage, and MCP. Added alerts cover decision-sink drops and triage/MCP
availability alongside existing proxy SLO alerts.

The three `/actuator/prometheus` endpoints are intentionally unauthenticated
for this static local scrape topology. Production deployments must keep those
service ports on a trusted monitoring network or add an authenticated scrape
configuration at the ingress layer.

Important failure meanings:

- sink drops mean incomplete triage evidence, not proxy request failure;
- `runbook_ingestion_deferred` means the embedding service was unavailable and
  ingestion should be retried;
- `TRIAGE_FAILED` means the bounded automated attempt budget was exhausted;
  inspect `triage_failure`, repair the cause, and use the authenticated manual
  triage endpoint. Stale `TRIAGING` leases are reclaimed automatically;
- a `valid=false` report is a validation fallback and must not be presented as
  model certainty;
- `APPLY_FAILED` requires examining `audit_events`, the proposal failure, proxy
  reload state, and the sibling `.bak.<random>` file;
- `APPLY_INDETERMINATE` means neither a completed apply nor a verified rollback
  can be proven; stop automatic retries and reconcile the recorded baseline and
  target policy digests;
- Ollama health or GPU placement does not affect deterministic detection or
  proxy request availability.

See [`../ops/TRIAGE_RUNBOOK.md`](../ops/TRIAGE_RUNBOOK.md) for concrete recovery
procedures.

## Detailed references

- [`triage/architecture.md`](triage/architecture.md) — classes, flows, APIs, and lifecycle
- [`triage/data-model.md`](triage/data-model.md) — contracts, tables, indexes, JSON, and retention
- [`triage/security.md`](triage/security.md) — tokens, trust boundaries, model/tool controls, and safe apply
- [`triage/configuration.md`](triage/configuration.md) — every runtime setting and deployment overlay
- [`triage/evaluation.md`](triage/evaluation.md) — exact test coverage, evaluation math, and limitations
- [`runbooks/`](runbooks/) — the trusted remediation corpus
- [`../maluca-triage/README.md`](../maluca-triage/README.md) — triage service developer/API guide
- [`../maluca-mcp/README.md`](../maluca-mcp/README.md) — MCP server/client guide
