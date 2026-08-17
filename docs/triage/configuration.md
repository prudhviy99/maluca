# AI Incident Triage: Configuration and Runtime Reference

This document describes the checked-in configuration and packaging. Values
shown as defaults are development defaults from the repository, not production
recommendations.

## Version and runtime requirements

- Java 21
- Spring Boot 3.5.15
- Spring AI 1.1.8
- PostgreSQL 16 with pgvector (Compose pins
  `pgvector/pgvector:0.8.5-pg16-bookworm`)
- Ollama (Compose pins `ollama/ollama:0.32.5`)
- default chat model `qwen3:14b`
- default embedding model `nomic-embed-text`, expected to produce 768
  dimensions

Flyway creates the database schema. Spring AI schema initialization is disabled
and schema validation is enabled, so the application must connect to a database
where the migration can enable `vector` and create `runbook_chunks`.

## Source-of-truth files

| Concern | File |
|---|---|
| Triage application defaults | `maluca-triage/src/main/resources/application.yml` |
| Triage typed properties | `maluca-triage/src/main/java/com/maluca/triage/config/TriageProperties.java` |
| Database schema | `maluca-triage/src/main/resources/db/migration/V1__triage_schema.sql` through `V5__bind_proposals_to_report_generation.sql` |
| Proxy sink defaults | `maluca-proxy/src/main/resources/application.yml` and `DecisionSinkProperties.java` |
| MCP defaults and bounds | `maluca-mcp/src/main/resources/application.yml` and `MalucaMcpProperties.java` |
| Container topology | `docker-compose.yml` plus `docker-compose.triage.yml` |
| Secret template | `.env.example` |
| Trusted corpus | `docs/runbooks/*.md` |

Spring Boot environment variables have higher precedence than YAML. In
addition to the explicit aliases below, a nested property can be overridden
with Spring's canonical uppercase/underscore form, for example
`MALUCA_TRIAGE_DETECTION_CURRENT_WINDOW=90s`.

## Triage environment variables

### Listener, database, and models

| Environment variable | Property | Checked-in default | Behavior |
|---|---|---|---|
| `TRIAGE_PORT` | `server.port` | `8082` | Triage HTTP listener. This is intentionally not the proxy's `PORT` alias. |
| `TRIAGE_DATABASE_URL` | `spring.datasource.url` | `jdbc:postgresql://localhost:5432/maluca` | JDBC URL. |
| `TRIAGE_DATABASE_USERNAME` | `spring.datasource.username` | `maluca` | Database user. |
| `TRIAGE_DATABASE_PASSWORD` | `spring.datasource.password` | `maluca-dev-password` | Development password; replace it. |
| `TRIAGE_DB_POOL_SIZE` | Hikari maximum pool size | `10` | Maximum JDBC connections. Connection timeout is fixed at 5 seconds in YAML unless overridden by a Spring property. |
| `OLLAMA_BASE_URL` | `spring.ai.ollama.base-url` | `http://localhost:11434` | Local chat and embedding service. |
| `OLLAMA_CHAT_MODEL` | chat option and recorded agent model | `qwen3:14b` | Model used by `ChatClient` and stored on reports. |
| `OLLAMA_EMBEDDING_MODEL` | embedding option | `nomic-embed-text` | Runbook/query embedding model. |
| `OLLAMA_CONTEXT_SIZE` | `spring.ai.ollama.chat.options.num-ctx` | `8192` | Chat context size. |
| `OLLAMA_SEED` | `spring.ai.ollama.chat.options.seed` | `42` | Deterministic chat sampling seed used by runtime inference and the Ollama regression suite. |
| `OLLAMA_INFERENCE_TIMEOUT` | `maluca.triage.agent.inference-timeout` | `90s` | Connect/read timeout on the dedicated Ollama API client used by both chat and embeddings. |
| `TRIAGE_AGENT_ORCHESTRATION_TIMEOUT` | `maluca.triage.agent.orchestration-timeout` | `4m` | Total retrieval/tool/model/repair deadline; must exceed inference timeout and is capped at 30 minutes. |
| `TRIAGE_AGENT_LEASE_TIMEOUT` | `maluca.triage.agent.lease-timeout` | `15m` | Claim fencing timeout; must exceed orchestration timeout and is capped at 24 hours. |
| `TRIAGE_AGENT_MAX_TOOL_CALLS` | `maluca.triage.agent.max-tool-calls` | `8` | Maximum MCP callback invocations in one orchestration; accepted range 0–50. |

Chat temperature is `0`, the seed defaults to 42, and model thinking is
explicitly disabled in the production `ChatClient`. Ollama's Spring AI pull strategy is `never`: a local
process does not pull models automatically. The Compose overlay handles pulls
with a separate `ollama-init` service.

The vector store is fixed to schema `public`, table `runbook_chunks`, cosine
distance, HNSW, and 768 dimensions. The migration column is also hard-coded as
`vector(768)`. Changing only an environment property or embedding model does
not migrate the column; change model, Spring vector-store dimension, triage
dimension check, migration, and regression baseline together.

Runbook ingestion has fixed defensive ceilings: 512 characters for a source
name, 256,000 Markdown characters per file, 256 per heading, 32,000 per chunk,
100 chunks per file, and 1,000 chunks for the complete corpus. These bounds are
code-level trust controls rather than environment tuning knobs.

### Authentication and privacy

| Environment variable | Property | Checked-in default | Behavior |
|---|---|---|---|
| `TRIAGE_API_TOKEN` | `maluca.triage.security.api-token` | `dev-triage-api-token` | Operator bearer token for `/api/**` and protected metrics/Flyway actuator endpoints. |
| `MALUCA_INTERNAL_TOKEN` | `maluca.triage.security.internal-token` | `dev-maluca-internal-token` | Internal header token shared with proxy sink and MCP upstream client. |
| `MCP_API_TOKEN` | `maluca.triage.security.mcp-token` | `dev-mcp-api-token` | Bearer token attached by the triage MCP client. |
| `TRIAGE_PSEUDONYMIZE_CLIENT_KEYS` | `maluca.triage.privacy.pseudonymize-client-keys` | `true` | HMAC-pseudonymize client keys before storage. |
| `TRIAGE_CLIENT_HMAC_KEY` | `maluca.triage.privacy.hmac-key` | `dev-client-hmac-key-change-me` | Stable HMAC secret; rotation breaks old/new client correlation. |

`maluca.triage.privacy.max-path-length` defaults to 512 and has no short alias.
Use `MALUCA_TRIAGE_PRIVACY_MAX_PATH_LENGTH` to override it. Truncation is by
Java character count, not UTF-8 byte count.

Replace every development token/key. Typed configuration rejects blank/control
character credentials, reused triage operator/internal/MCP credentials, an
enabled pseudonymizer with a short HMAC key, malformed upstream origins, and
unsafe numeric/duration bounds. The checked-in development values are distinct
and intentionally permit local startup; they are not production credentials.

### Feature switches and upstreams

| Environment variable | Property | Default | Behavior |
|---|---|---|---|
| `TRIAGE_DETECTOR_ENABLED` | `maluca.triage.detection.enabled` | `true` | Creates the scheduled detector bean only when true. |
| `TRIAGE_AGENT_ENABLED` | `maluca.triage.agent.enabled` | `true` | The scheduled worker silently no-ops when false; manual triage returns a disabled conflict. AI beans still configure normally. |
| `TRIAGE_INGEST_RUNBOOKS_ON_STARTUP` | `maluca.triage.retrieval.ingest-on-startup` | `true` | Ingest the trusted corpus at startup. Transient Ollama/network failure defers while preserving any stored last-good corpus; an empty/malformed corpus fails startup closed. |
| `TRIAGE_RUNBOOK_LOCATION` | `maluca.triage.retrieval.runbook-location` | `classpath*:runbooks/*.md` | Spring resource pattern for trusted Markdown. |
| `MALUCA_MCP_CLIENT_ENABLED` | `spring.ai.mcp.client.enabled` | `false` | Discover MCP callbacks for the agent. |
| `MALUCA_MCP_URL` | MCP connection URL | `http://localhost:8083` | MCP origin; endpoint is `/mcp`. |
| `POLICY_FILE` | `maluca.triage.policy.file` | `./config/policies.yml` | Existing YAML file that proposals hash and apply mutates. |
| `TRIAGE_POLICY_APPLY_ENABLED` | `maluca.triage.policy.apply-enabled` | `false` | Enable triage's apply service. Does not enable MCP's human tool. |
| `MALUCA_PROXY_URL` | `maluca.triage.upstreams.proxy-base-url` | `http://localhost:8080` | Proxy admin API origin used for reload/verification. |
| `MALUCA_ADMIN_TOKEN` | `maluca.triage.upstreams.proxy-admin-token` | `dev-admin-token` | Sent as `X-Maluca-Admin-Token`. |
| `TRIAGE_PROXY_CONNECT_TIMEOUT` | `maluca.triage.upstreams.proxy-connect-timeout` | `2s` | Per-request proxy connection timeout; startup requires `100ms`–`30s`. |
| `TRIAGE_PROXY_READ_TIMEOUT` | `maluca.triage.upstreams.proxy-read-timeout` | `5s` | Whole-response proxy deadline; startup requires `100ms`–`60s`. |
| `PROMETHEUS_URL` | `maluca.triage.upstreams.prometheus-base-url` | `http://localhost:9090` | Triage's bounded global Redis-error instant query origin; MCP independently uses the same alias for general bounded metric tools. |

MCP client type is synchronous with a 20-second request timeout. It adds the
configured bearer token to MCP requests. Discovered tools are filtered again
by the triage agent's name allowlist. It connects only to the agent-safe `/mcp`
endpoint, never `/operator/mcp`.

The triage Prometheus client has fixed two-second connect and five-second read
timeouts and queries only the configured origin. It currently has no bearer
property; place Prometheus on the trusted service network or add authentication
at the deployment proxy.

The proxy admin client applies its configured connection timeout and
whole-response deadline to every reload and policy-state verification request,
and it never follows redirects carrying the admin credential. Policy apply,
rollback, and scheduled reconciliation make a finite number of these calls, so
a stalled or slow-drip proxy cannot retain the cluster-wide apply lock
indefinitely. Timeout failures follow the same audited failure/indeterminate and
later-reconciliation paths as other proxy communication failures.

### Ingest defaults

| Property | Default | Use |
|---|---:|---|
| `maluca.triage.ingest.max-batch-size` | `500` | Reject a larger decision batch. |
| `maluca.triage.privacy.max-path-length` | `512` | Truncate an accepted path. |

These must remain aligned with proxy sink batch size. A proxy batch larger than
triage's maximum currently receives HTTP 400 (`IllegalArgumentException`) and
is permanently dropped as one batch; the sink does not bisect rejected
batches.

### Detector defaults

| Property | Default | Implemented use |
|---|---:|---|
| `maluca.triage.detection.current-window` | `60s` | Current aggregate interval. |
| `maluca.triage.detection.baseline-window` | `15m` | Immediately preceding baseline interval. |
| `maluca.triage.detection.poll-interval` | `15s` | Fixed delay between detector completions. |
| `maluca.triage.detection.resolve-after` | `5m` | Required time since the last anomalous stats window before resolution. |
| `minimum-mitigated` | `30` | Absolute mitigation count floor. |
| `minimum-mitigation-share` | `0.25` | Current mitigation-share floor. |
| `mitigation-multiplier` | `3.0` | Required multiple of baseline share. |
| `challenge-block-threshold` | `20` | Computed challenge+block surge threshold. |
| `traffic-volume-floor` | `100` | Absolute traffic count floor. |
| `traffic-volume-multiplier` | `4.0` | Required multiple of normalized baseline volume. |
| `redis-error-threshold` | `1` | Redis-error count threshold for both the global Prometheus increase and per-policy `redis_down%` decision fallback. |
| `sample-limit` | `50` | Maximum decision rows in the agent brief. |
| `top-value-limit` | `10` | Top contribution, client, and path values stored in stats. |

Rule priority and zero-baseline behavior are described in
[`architecture.md`](architecture.md). Typed startup validation requires all
count thresholds to be positive, mitigation share to be in `0..1`, both
multipliers to be finite and in `1..1000`, a baseline window longer than the
current window, sample limit `1..200`, and top-value limit `1..100`. Validate
non-default tuning in tests before deployment as well.

Each poll queries
`sum(increase(maluca_redis_errors_total[<current-window>]))`. A threshold hit
creates/evaluates the synthetic `__maluca_redis__` policy window; per-policy
decision-reason counts are always evaluated as the fallback evidence source.
An unavailable/malformed Prometheus response is “unknown,” not normal, so an
existing synthetic Redis incident is not auto-resolved until the query works.

### Agent and retrieval defaults

| Property | Default | Implemented use |
|---|---:|---|
| `maluca.triage.agent.poll-interval` | `10s` | Fixed delay between scheduled triage claims. |
| `maluca.triage.agent.inference-timeout` | `90s` | Ollama HTTP connect/read bound, capped at 15 minutes. Spring AI retries are capped at two attempts. |
| `maluca.triage.agent.orchestration-timeout` | `4m` | Total retrieval, tools, model, and repair deadline; must exceed inference timeout and is capped at 30 minutes. |
| `maluca.triage.agent.lease-timeout` | `15m` | A `TRIAGING` claim older than this is fenced, reclaimed, and deferred or failed terminally. Must exceed orchestration timeout and is capped at 24 hours. |
| `maluca.triage.agent.max-tool-calls` | `8` | Per-orchestration callback budget; accepted range 0–50. |
| `maluca.triage.agent.max-attempts` | `3` | Total claims, including authorized manual retries, before `TRIAGE_FAILED`; accepted range 1–20. |
| `maluca.triage.agent.retry-base-delay` | `30s` | First failure delay. |
| `maluca.triage.agent.retry-max-delay` | `5m` | Cap for doubling retry delays. |
| `maluca.triage.agent.max-brief-characters` | `16000` | Hard complete incident-brief character budget; accepted range 4,096–100,000. |
| `maluca.triage.agent.max-sample-characters` | `1200` | Hard JSON character budget for each selected decision; minimum 512. |
| `maluca.triage.agent.max-sample-contributions` | `8` | Highest numeric contributions retained per decision; accepted range 1–64. |
| `maluca.triage.agent.model` | `qwen3:14b` | Model label stored in reports. |
| `maluca.triage.agent.prompt-version` | `v4` | Prompt label stored in reports and eval baseline. |
| `maluca.triage.agent.repair-attempts` | `1` | One bounded correction call after the first failed parse/validation. |
| `maluca.triage.agent.max-summary-words` | `150` | Java gate limit. |
| `maluca.triage.agent.max-evidence-items` | `12` | Java gate limit. |
| `maluca.triage.retrieval.top-k` | `6` | Default vector result count. |
| `maluca.triage.retrieval.similarity-threshold` | `0.45` | Minimum Spring AI similarity score. |
| `maluca.triage.retrieval.embedding-dimensions` | `768` | Runtime embedding-model sanity check. |
| `maluca.triage.retrieval.embedding-model` | `nomic-embed-text` | Identity persisted with each chunk; a change forces re-embedding even when Markdown hashes are unchanged. |

The default allowed tool names are:

```text
get_incidents
get_decisions
get_signal_breakdown
query_metrics
list_policies
search_runbooks
```

The list can be narrowed through the full property
`maluca.triage.agent.allowed-tools`, but it cannot be expanded beyond this
code-owned read-only set: startup rejects any other name. A listed name still
requires a callback actually discovered from MCP. The public agent MCP endpoint
exposes `propose_policy_patch` for post-triage external use, but the autonomous
triage `ChatClient` can receive only these six read-only callbacks. Its
validated JSON patch is persisted transactionally with the report instead.

A repair-exhausted `UNKNOWN`/`LOW` fallback is stored for diagnosis but is a
failed claim, not a successful `TRIAGED` result. It follows the same base/max
backoff and attempt limit as transport/dependency failures, ending in
`TRIAGE_FAILED` until an authorized manual triage call retries it.

### Retention and policy defaults

| Property | Default | Implemented use |
|---|---:|---|
| `maluca.triage.retention.decisions` | `7d` | Age from `occurred_at` at which decisions are deleted. |
| `maluca.triage.retention.purge-cron` | `0 17 * * * *` | Spring six-field cron: minute 17 of every hour. |
| `maluca.triage.policy.default-observe-min` | `30` | Fallback for omitted band patch field. |
| `default-soft-limit-min` | `50` | Same. |
| `default-hard-limit-min` | `65` | Same. |
| `default-challenge-min` | `75` | Same. |
| `default-block-min` | `90` | Same. |
| `maluca.triage.policy.backup-retention` | `10` | Number of newest policy backups retained after successful apply. |
| `maluca.triage.policy.reconcile-interval` | `30s` | Scan up to 20 durable `APPROVED` proposals for crash recovery under the cluster apply lock. |

Only decisions are automatically pruned. The policy default bands are
validation fallbacks, not values read dynamically from the current policy.

## Proxy decision-sink configuration

The proxy remains standalone because the sink is off by default.

| Environment/property | Default | Behavior |
|---|---:|---|
| `MALUCA_DECISION_SINK_ENABLED` | `false` | Start the virtual-thread drainer and allocate/export events. |
| `MALUCA_DECISION_SINK_ENDPOINT` | `http://localhost:8082/internal/v1/decisions` | Fixed HTTP(S) POST destination. |
| `MALUCA_INTERNAL_TOKEN` | blank | Header value; required at startup when the sink is enabled. |
| `maluca.decision-sink.auth-header` | `X-Maluca-Internal-Token` | Header name. |
| `maluca.decision-sink.queue-capacity` | `10000` | Maximum queued events, excluding one in-flight batch. |
| `maluca.decision-sink.batch-size` | `500` | Maximum batch and threshold for immediate drain. |
| `maluca.decision-sink.flush-interval` | `1s` | Partial-batch wake interval. |
| `maluca.decision-sink.request-timeout` | `3s` | HTTP connect and per-request timeout. |
| `maluca.decision-sink.initial-backoff` | `250ms` | First retry delay. |
| `maluca.decision-sink.max-backoff` | `30s` | Retry cap. |
| `maluca.decision-sink.shutdown-timeout` | `5s` | Bounded flush window. |

The property record rejects a non-HTTP(S) endpoint, blank header, enabled sink
without a token, non-positive numeric/duration bounds, batch size over queue
capacity, and initial backoff over maximum backoff.

HTTP 408 and 429 responses remain in flight and retry with capped exponential
backoff, as do transport failures and other non-2xx responses outside the 4xx
range. Every other 4xx response is treated as a permanent contract or
authentication rejection: the whole batch is discarded without bisection,
later evidence can progress, and the affected event count increments
`maluca_sink_permanent_dropped_total`.

The Compose overlay enables the sink and points it to the service name
`maluca-triage`.

## MCP configuration summary

The full MCP reference is
[`../../maluca-mcp/README.md`](../../maluca-mcp/README.md). Triage operators
must align these key variables:

| Variable | Default in MCP app / overlay | Purpose |
|---|---|---|
| `PORT` | `8083` | MCP listener. |
| `MCP_API_TOKEN` | blank in app; development token in overlay | Agent read/propose bearer. |
| `MCP_APPLY_TOKEN` | blank in app; separate development token in overlay | Human operator bearer. |
| `MALUCA_MCP_APPLY_ENABLED` | `false` | Create/publish the human apply provider. |
| `TRIAGE_API_TOKEN` | blank in MCP app; triage development token in overlay | Dedicated triage operator bearer used only by MCP's apply client (`maluca.mcp.triage-approval-token`). |
| `MALUCA_TRIAGE_URL` | `http://localhost:8082` | Triage API origin. |
| `MALUCA_INTERNAL_TOKEN` | blank | Triage upstream credential. |
| `MALUCA_PROXY_URL` | `http://localhost:8080` | Proxy origin. |
| `MALUCA_ADMIN_TOKEN` | blank | Proxy admin credential. |
| `PROMETHEUS_URL` | `http://localhost:9090` | Metrics origin. |
| `PROMETHEUS_BEARER_TOKEN` | blank | Optional metrics credential. |

MCP defaults to a 1 MiB triage/proxy response cap, 2 MiB Prometheus cap,
2-second connect timeout, 5-second triage/proxy read timeout, and 8-second
Prometheus read timeout. General result count is 100 capped at 200; runbook
count is 8 capped at 12; evidence windows are capped at 24 hours. Its PromQL
policy caps the outer range at six hours, step at no less than 15 seconds,
returned/requested samples at 5,000, and series at 50.

Triage and MCP both cap vector retrieval requests at 12.

MCP serves agent discovery/calls at `/mcp`. Even with apply enabled, that
endpoint exposes only the seven read/propose tools. The opt-in
`/operator/mcp` endpoint requires `MCP_APPLY_TOKEN` and exposes only
`approve_and_apply`; an agent credential cannot discover it.

## Docker Compose overlay

Validate and run the opt-in stack from the repository root:

```bash
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage config --quiet

docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage up --build
```

The overlay adds or modifies:

| Service | Runtime behavior |
|---|---|
| `postgres` | Binds 5432 to host loopback, uses a named data volume, and gates health with `pg_isready`. |
| `ollama` | Binds 11434 to host loopback and persists models in a named volume. |
| `ollama-init` | One-shot `ollama pull` for chat then embedding model; triage waits for successful completion. |
| `maluca-proxy` | Enables the sink, shares the internal token, and mounts `./config` read-only at `/app/config`. |
| `prometheus` | Is included in the triage profile for the detector's global Redis signal and MCP metrics queries. |
| `maluca-mcp` | Builds the MCP image, binds 8083 to host loopback, uses fixed service URLs, and is healthy before triage starts. |
| `maluca-triage` | Builds the triage image, binds 8082 to host loopback, connects to service names, enables its MCP client, and mounts `./config` writable. |

The overlay intentionally requires both independent flags for a human MCP
apply path:

```dotenv
TRIAGE_POLICY_APPLY_ENABLED=true
MALUCA_MCP_APPLY_ENABLED=true
```

Keep both false until the policy directory is correctly mounted, agent/apply
tokens differ, and the human review workflow is ready.

The triage overlay binds PostgreSQL, Ollama, triage, MCP, and Prometheus only
to host loopback and does not by
itself request a GPU. Add `-f docker-compose.gpu.yml` to the command for the
checked-in NVIDIA device reservation. Restrict published ports in a production
overlay. Verify the actual Ollama processor with `ollama ps`; CPU execution is
possible and slower.

The triage image is a two-stage Java 21 build. It copies the shared contracts,
triage source, and runbook directory, builds `:maluca-triage:bootJar`, and runs
on a Java 21 JRE with `-XX:MaxRAMPercentage=70 -XX:+UseG1GC` by default.

## Local non-container run

With PostgreSQL/pgvector and Ollama already available:

```bash
export TRIAGE_DATABASE_URL='jdbc:postgresql://localhost:5432/maluca'
export TRIAGE_DATABASE_USERNAME='maluca'
export TRIAGE_DATABASE_PASSWORD='replace-me'
export TRIAGE_API_TOKEN='replace-me'
export MALUCA_INTERNAL_TOKEN='replace-me'
export TRIAGE_CLIENT_HMAC_KEY='replace-me'
export OLLAMA_BASE_URL='http://localhost:11434'

./gradlew :maluca-triage:bootRun
```

If MCP is not running, keep `MALUCA_MCP_CLIENT_ENABLED=false`. The agent can
still use the incident brief and local pgvector retrieval. If Ollama is not
available, set `TRIAGE_AGENT_ENABLED=false` and
`TRIAGE_INGEST_RUNBOOKS_ON_STARTUP=false` to run decision ingestion and the
detector without repeatedly attempting AI work. Readiness is `UP` only if a
last-good runbook corpus already exists; a fresh database remains
`OUT_OF_SERVICE`. Existing vectors still require the configured embedding
service for new semantic queries.

## Startup and dependency behavior

1. Spring binds properties and creates JDBC/AI/vector-store clients.
2. Flyway validates/applies migrations.
3. Spring AI validates the existing vector schema.
4. Schedulers become active.
5. `RunbookIngestionService` attempts startup ingestion when enabled.

An unavailable PostgreSQL/schema is a startup failure. A transient
Ollama/network ingestion failure retains a stored last-good corpus but leaves a
fresh database not ready. Empty/malformed corpora, dimension mismatch, and
other permanent ingestion failures abort startup. MCP upstreams need not be live for the MCP
process to start; individual tool calls report bounded errors.

An unavailable triage endpoint does not fail proxy startup. For retryable
failures, the proxy sink keeps its current batch and bounds all newer queued
data.

## Health and metrics

```bash
curl --fail http://localhost:8080/actuator/health
curl --fail http://localhost:8082/actuator/health
curl --fail http://localhost:8083/actuator/health
curl --fail http://localhost:11434/api/tags
```

Triage exposes `health`, `info`, `metrics`, `prometheus`, and `flyway` actuator
endpoints. Health, info, and Prometheus are public; metrics/Flyway require the
triage operator bearer. MCP health and Prometheus are public, while MCP info
requires an agent/operator token. The proxy Prometheus endpoint is also public.
This is deliberate for the checked-in static scrape topology; production must
restrict all three service ports to the Prometheus network.

Dedicated metric names after Micrometer Prometheus normalization are:

```text
maluca_sink_queue_size
maluca_sink_dropped_total
maluca_sink_permanent_dropped_total
maluca_sink_success_total
maluca_sink_failure_total
maluca_triage_ingest_accepted_total
maluca_triage_ingest_duplicates_total
maluca_triage_incidents_opened_total
maluca_triage_incidents_resolved_total
maluca_triage_agent_reports_total{result="valid"|"fallback"}
maluca_triage_agent_claims_total{result="deferred"|"manual_review"|"lease_reclaimed"|"stale_result"}
```

`maluca_sink_success_total` and `maluca_sink_permanent_dropped_total` count
events. `maluca_sink_failure_total` counts failed batch attempts, including the
single attempt that caused a permanent drop. `maluca_sink_dropped_total` is
reserved for bounded-queue eviction and shutdown abandonment.

## Configuration invariants

Before deployment, verify:

- proxy and triage use the same internal token;
- triage and MCP use the same MCP agent token;
- MCP and triage use the same internal token for upstream calls;
- MCP's dedicated triage approval token matches triage's operator bearer and is
  used only for apply;
- triage and MCP use the same proxy admin token;
- agent, human-apply, internal-service, triage-operator, and proxy-admin tokens
  satisfy MCP's enforced separation rules;
- embedding model, vector-store setting, migration, and triage dimension check
  all remain 768;
- proxy batch size is no larger than triage max batch size;
- both policy mounts refer to the same host directory and remain read-only in
  the proxy. Proposal-only triage needs read access; apply-capable triage needs
  the policy file and parent directory writable;
- policy application flags are both false unless human apply is deliberately
  enabled;
- the HMAC key is stable and secret;
- model names match the models pulled by `ollama-init`; and
- no checked-in development credential is used outside local development.
