# Maluca Triage Service

`maluca-triage` is Maluca's optional incident control plane. It receives a
lossy stream of proxy decisions, opens incidents with deterministic rules,
retrieves trusted runbook sections from PostgreSQL/pgvector, asks a local
Ollama model for a typed report, and rejects any output that is not grounded
in the frozen incident evidence and retrieved citations.

The service is deliberately outside the proxy request path. PostgreSQL,
Ollama, MCP, and this service can all be unavailable without preventing the
proxy from serving traffic.

If these concepts are new, start with the root
[beginner guide](../docs/beginner-guide.md#part-ii-the-ai-incident-triage-control-plane).
For the measured `gemma4:e4b` commands and results, see its
[testing section](../docs/beginner-guide.md#41-testing-strategy-and-what-each-layer-proves).

## Runtime flow

1. The proxy offers each completed decision to a bounded, asynchronous queue.
2. Batches arrive at `POST /internal/v1/decisions`; duplicate event UUIDs are
   ignored and client keys are HMAC-pseudonymized before storage.
3. The detector compares a 60-second window with a 15-minute baseline and
   opens at most one active incident per policy. It also evaluates the global
   Prometheus increase of `maluca_redis_errors_total` as synthetic policy
   `__maluca_redis__`; per-policy `redis_down%` decisions remain the fallback
   when that query is unavailable. The opening aggregate is frozen in
   `incidents.stats`; later anomalous polls only update activity.
4. A worker atomically claims an eligible `OPEN` incident with `FOR UPDATE
   SKIP LOCKED` and a fenced PostgreSQL lease. It builds a selected-field brief
   with per-sample and whole-request budgets, then retrieves the top runbook
   chunks through Spring AI's `PgVectorStore`.
5. Spring AI calls the configured Ollama chat model with thinking disabled, a
   strict structured output contract, a total deadline, and a call-budgeted
   allowlist of read-only MCP tools.
6. Java validation checks evidence, exact citation metadata, output size, and
   any typed policy patch. One repair attempt is allowed. Persistent failure
   stores an honest `UNKNOWN`/`LOW` fallback for diagnosis, returns the claim
   to exponential backoff, and eventually reaches `TRIAGE_FAILED` for an
   explicit manual retry instead of marking it successfully triaged.
7. A gate-valid model patch is recorded with the exact report-generation
   provenance; external proposals require that current valid report. Application remains a separate,
   disabled-by-default operator action bound to the exact proposal ID/digest,
   baseline policy digest, and incident version. A cluster advisory lock,
   target-content digest, atomic file replacement, proxy verification,
   compensation, and crash reconciler guard the external mutation.

## Start it

The supported full-stack path is the root Compose overlay:

```bash
cp .env.example .env
# Replace every development secret in .env.
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage up --build
```

The first start pulls `qwen3:14b` and `nomic-embed-text`. Add
`-f docker-compose.gpu.yml` for an NVIDIA runtime. CPU mode works for
functional testing but 14B inference can be slow.

For direct development, provide PostgreSQL 16 with the `vector`, `hstore`, and
`uuid-ossp` extensions and run Ollama separately:

```bash
ollama pull qwen3:14b
ollama pull nomic-embed-text

TRIAGE_DATABASE_URL=jdbc:postgresql://localhost:5432/maluca \
TRIAGE_DATABASE_USERNAME=maluca \
TRIAGE_DATABASE_PASSWORD=local-password \
TRIAGE_API_TOKEN=local-operator-token \
MALUCA_INTERNAL_TOKEN=local-internal-token \
TRIAGE_CLIENT_HMAC_KEY=local-long-random-hmac-key \
./gradlew :maluca-triage:bootRun
```

Flyway migrates the schema at startup. Runbook ingestion also starts
automatically. A configured corpus that resolves to zero Markdown files fails
startup closed, because continuing would silently serve an empty knowledge
base. If Ollama is temporarily unavailable, ingestion instead logs a deferred
warning. A stored last-good corpus keeps readiness up only when every row has
the configured embedding-model identity and trusted, bounded metadata/content;
a fresh or mismatched database stays `OUT_OF_SERVICE` and the worker consumes
no claim attempts. Retry with the ingest endpoint after Ollama returns.

## Authentication and endpoints

Health, info, and `/actuator/prometheus` are public. Other endpoints fail
closed; the public scrape endpoint assumes service-port network restriction.

| Credential | Header | Allowed use |
|---|---|---|
| Proxy/service token | `X-Maluca-Internal-Token` | Decision ingest, read APIs, and proposal creation; never policy apply |
| Operator token | `Authorization: Bearer …` | Read APIs, proposal creation, actuator details, and guarded apply |

### Decision and signal APIs

| Method and path | Purpose |
|---|---|
| `POST /internal/v1/decisions` | Idempotently ingest a batch of at most 500 decision events |
| `GET /api/v1/decisions` | Query up to 200 decisions by policy, pseudonymous client, computed action, and time |
| `GET /api/v1/signals` | Sum score contributions for one policy over at most 24 hours |

### Incident and report APIs

| Method and path | Purpose |
|---|---|
| `GET /api/v1/incidents` | List up to 100 recent incidents, optionally by status |
| `GET /api/v1/incidents/{id}` | Get one incident and its frozen aggregate |
| `POST /api/v1/incidents/{id}/triage` | Manually claim and triage an `OPEN` or `TRIAGE_FAILED` incident |
| `POST /api/v1/incidents/{id}/dismiss` | Operator-only exact-version dismissal of an open `TRIAGE_FAILED` incident with a bounded reason |
| `GET /api/v1/incidents/{id}/report` | Get the typed report, citations, and persisted retrieval context |
| `GET /api/v1/incidents/{id}/report.md` | Render the same report as Markdown |

### Runbook APIs

| Method and path | Purpose |
|---|---|
| `GET /api/v1/runbooks/search?query=…&k=6` | Semantic search with a hard top-k cap of 12 |
| `POST /api/v1/runbooks/ingest` | Checksum-aware re-ingestion after corpus or model availability changes |

### Remediation APIs

| Method and path | Purpose |
|---|---|
| `POST /api/v1/proposals` | Store a validated route-scoped delta for a `TRIAGED` incident with a current valid report |
| `GET /api/v1/proposals/{id}` | Fetch one exact proposal with its proposal, baseline, and optional target digests |
| `GET /api/v1/incidents/{id}/proposals?limit=20` | List that incident's proposals newest-first; limit is clamped to 1–100 |
| `POST /api/v1/incidents/{id}/apply` | Operator-only approval and application; disabled by default |
| `POST /api/v1/incidents/{id}/reconcile-policy` | Operator-only digest-derived reconciliation of `APPLY_INDETERMINATE` |

Example proposal:

```bash
curl -X POST http://localhost:8082/api/v1/proposals \
  -H "Authorization: Bearer $TRIAGE_API_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "incidentId": "INCIDENT_UUID",
    "patch": {
      "policyName": "login",
      "route": "/login",
      "mode": "DRY_RUN",
      "rateLimit": {
        "algorithm": "SLIDING_WINDOW_LOG",
        "limit": 4,
        "windowSeconds": 60
      },
      "rationale": "Stage a narrower login limit and review false positives"
    }
  }'
```

The response includes the proposal UUID, canonical `proposalSha256`, and
proposal-time baseline `policySha256`. Select and review one exact proposal,
then re-fetch it and the incident immediately before approval. Do not choose a
proposal implicitly by recency:

```bash
INCIDENT_ID='replace-with-incident-uuid'
PROPOSAL_ID='replace-with-reviewed-proposal-uuid'
APPROVED_BY='replace-with-human-identity'

PROPOSAL_JSON="$(curl -fsS \
  -H "Authorization: Bearer ${TRIAGE_API_TOKEN}" \
  "http://localhost:8082/api/v1/proposals/${PROPOSAL_ID}")"
INCIDENT_JSON="$(curl -fsS \
  -H "Authorization: Bearer ${TRIAGE_API_TOKEN}" \
  "http://localhost:8082/api/v1/incidents/${INCIDENT_ID}")"

test "$(printf '%s' "${PROPOSAL_JSON}" | jq -r '.incidentId')" = "${INCIDENT_ID}"
test "$(printf '%s' "${PROPOSAL_JSON}" | jq -r '.status')" = 'PROPOSED'

EXPECTED_PROPOSAL_SHA256="$(printf '%s' "${PROPOSAL_JSON}" | jq -er '.proposalSha256')"
EXPECTED_POLICY_SHA256="$(printf '%s' "${PROPOSAL_JSON}" | jq -er '.policySha256')"
EXPECTED_INCIDENT_VERSION="$(printf '%s' "${INCIDENT_JSON}" | jq -er '.version')"

jq -n \
  --arg proposalId "${PROPOSAL_ID}" \
  --arg proposalSha "${EXPECTED_PROPOSAL_SHA256}" \
  --arg policySha "${EXPECTED_POLICY_SHA256}" \
  --argjson incidentVersion "${EXPECTED_INCIDENT_VERSION}" \
  --arg approvedBy "${APPROVED_BY}" \
  '{proposalId:$proposalId,
    expectedProposalSha256:$proposalSha,
    expectedPolicySha256:$policySha,
    expectedIncidentVersion:$incidentVersion,
    approvedBy:$approvedBy}' | \
curl -fsS -X POST "http://localhost:8082/api/v1/incidents/${INCIDENT_ID}/apply" \
  -H "Authorization: Bearer $TRIAGE_API_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @- | jq .
```

Direct apply additionally requires `TRIAGE_POLICY_APPLY_ENABLED=true`.
When using MCP, `MALUCA_MCP_APPLY_ENABLED=true` and the separate MCP approval
credential are also required. Agent tools are always served at `/mcp` and
cannot discover apply; the human-only tool is served separately at
`/operator/mcp` and publishes only `approve_and_apply`.

## Configuration reference

The defaults live in `src/main/resources/application.yml`.

| Environment variable | Default | Meaning |
|---|---:|---|
| `TRIAGE_PORT` | `8082` | HTTP port |
| `TRIAGE_DATABASE_URL` | `jdbc:postgresql://localhost:5432/maluca` | JDBC URL |
| `TRIAGE_DATABASE_USERNAME` | `maluca` | Database role |
| `TRIAGE_DATABASE_PASSWORD` | development value | Database password |
| `TRIAGE_API_TOKEN` | development value | Operator bearer token |
| `MALUCA_INTERNAL_TOKEN` | development value | Proxy/MCP service token |
| `TRIAGE_CLIENT_HMAC_KEY` | development value | HMAC key for client pseudonyms |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama API |
| `OLLAMA_CHAT_MODEL` | `qwen3:14b` | Structured report model |
| `OLLAMA_EMBEDDING_MODEL` | `nomic-embed-text` | 768-dimensional embedding model |
| `OLLAMA_CONTEXT_SIZE` | `8192` | Model context window |
| `OLLAMA_SEED` | `42` | Deterministic chat sampling and regression seed |
| `OLLAMA_INFERENCE_TIMEOUT` | `90s` | Chat/embedding HTTP connect and read timeout |
| `TRIAGE_AGENT_ORCHESTRATION_TIMEOUT` | `4m` | Total retrieval/tool/model/repair deadline |
| `TRIAGE_AGENT_LEASE_TIMEOUT` | `15m` | Reclaim a crashed worker's stale `TRIAGING` lease |
| `TRIAGE_AGENT_MAX_TOOL_CALLS` | `8` | Maximum MCP callbacks per attempt |
| `TRIAGE_AGENT_MAX_ATTEMPTS` | `3` | Fail to `TRIAGE_FAILED` after this many claims |
| `TRIAGE_AGENT_RETRY_BASE_DELAY` | `30s` | Initial failed-triage backoff |
| `TRIAGE_AGENT_RETRY_MAX_DELAY` | `5m` | Exponential-backoff cap |
| `TRIAGE_AGENT_MAX_BRIEF_CHARACTERS` | `16000` | Complete untrusted brief budget |
| `TRIAGE_AGENT_MAX_SAMPLE_CHARACTERS` | `1200` | Per-decision JSON budget |
| `TRIAGE_AGENT_MAX_SAMPLE_CONTRIBUTIONS` | `8` | Highest contributions retained per sample |
| `MALUCA_MCP_CLIENT_ENABLED` | `false` | Enable remote MCP tool discovery |
| `MALUCA_MCP_URL` | `http://localhost:8083` | MCP base URL |
| `MCP_API_TOKEN` | development value | Agent/read-propose MCP token |
| `MCP_APPLY_TOKEN` | development value | Separate human/operator MCP token |
| `MALUCA_MCP_APPLY_ENABLED` | `false` | Publish `/operator/mcp` and its sole apply tool |
| `POLICY_FILE` | `./config/policies.yml` | Writable external policy file |
| `TRIAGE_POLICY_APPLY_ENABLED` | `false` | First apply safety switch |
| `MALUCA_PROXY_URL` | `http://localhost:8080` | Proxy admin API origin for reload and verification |
| `TRIAGE_PROXY_CONNECT_TIMEOUT` | `2s` | Per-request proxy connection timeout; allowed range `100ms`–`30s` |
| `TRIAGE_PROXY_READ_TIMEOUT` | `5s` | Whole-response proxy deadline; allowed range `100ms`–`60s` |
| `TRIAGE_PSEUDONYMIZE_CLIENT_KEYS` | `true` | HMAC client identifiers on ingest |
| `TRIAGE_INGEST_RUNBOOKS_ON_STARTUP` | `true` | Embed changed chunks at startup |
| `TRIAGE_DETECTOR_ENABLED` | `true` | Run deterministic detection polls |
| `TRIAGE_AGENT_ENABLED` | `true` | Claim and triage open incidents |
| `PROMETHEUS_URL` | `http://localhost:9090` | Global Redis-error query origin |

Proxy reload and verification calls use a bounded connection timeout and a
whole-response deadline; redirects are not followed with the admin credential.
An unreachable, slow-drip, or stalled proxy therefore fails the current
apply/reconciliation attempt instead of holding the cluster-wide policy lock
indefinitely. Triage retains its normal rollback, indeterminate-state, audit,
and scheduled-reconciliation behavior.

Failures return the incident to `OPEN` only after an exponentially increasing
eligibility delay. At the attempt limit it becomes `TRIAGE_FAILED`, remains the
single active incident for that policy, and requires an explicit authorized
retry through `POST /api/v1/incidents/{id}/triage`. Startup and every worker
poll reclaim leases older than the configured timeout; the lease UUID fences
late results.

Do not change the embedding model to a different dimension without adding a
database migration and changing both pgvector configuration and
`maluca.triage.retrieval.embedding-dimensions`.

## Data model

Flyway owns five migrations:

- `V1__triage_schema.sql` installs extensions and creates `decisions`,
  `incidents`, `triage_reports`, `policy_proposals`, `audit_events`, and the
  Spring AI-compatible `runbook_chunks` table with a 768-dimensional HNSW
  cosine index.
- `V2__persist_retrieval_context.sql` persists the retrieved chunks and scores
  used for each report.
- `V3__bind_policy_approvals.sql` adds canonical proposal and target policy
  digests, their checks/index, and indeterminate-active incident semantics.
- `V4__incident_triage_leases.sql` adds fenced claim, attempt, retry, and
  failure columns/indexes.
- `V5__bind_proposals_to_report_generation.sql` binds every pending proposal
  to an exact valid report generation, quarantines legacy pending rows, and
  deduplicates repeated report/patch/actor submissions.

Decision UUIDs make ingestion idempotent. A partial unique index permits only
one active incident per policy. Exact proposal ID/digest and incident version
protect what was reviewed; baseline/target SHA-256 values protect the file
transition and recovery. Decision rows are purged after seven days by default.
Reports, proposals, and audit records are retained until an explicit retention
policy is added.

## Safety properties

- Incident opening is deterministic Java/SQL; the model cannot create alerts.
- The incident aggregate is frozen and sampled rows are bounded.
- Attacker-controlled evidence is wrapped in an explicit untrusted delimiter.
- Tool callbacks are selected by exact name; autonomous triage receives six
  read-only tools. `propose_policy_patch` remains on the external agent MCP
  endpoint, while `approve_and_apply` exists only on the operator endpoint.
- Non-`UNKNOWN` classifications require an exact citation from the retrieved
  set. Source and heading must also match.
- Every evidence fact/value pair must occur together in the frozen brief;
  unrelated copied values and invented pairs fail.
- The Markdown projection escapes model-controlled HTML and Markdown syntax.
- Policy changes are typed fields, never arbitrary YAML, paths, shell, or SQL.
- Rate algorithms, bands, CIDRs, route identity, and rationale are validated in
  Java; CIDR parsing accepts literal addresses and never resolves DNS.
- Apply is off by default, operator-only, audited, cluster-serialized with a
  PostgreSQL advisory lock, exact-proposal and baseline/target-digest guarded,
  written with same-directory atomic replacement, reloaded, verified against
  the proxy's compiled policy, compensated on later persistence failure, and
  marked `APPLY_INDETERMINATE` when rollback cannot be verified. Durable
  `APPROVED` crash gaps are reconciled on a 30-second schedule.
- Runbook ingestion is cluster-serialized with a transaction-scoped PostgreSQL
  advisory lock. Changed chunks are embedded and upserted before obsolete rows
  are deleted, all in one database transaction, so a failed replacement leaves
  the last-good corpus intact.

## Tests

```bash
# Unit, deterministic retrieval/validation regressions, and pgvector migration
./gradlew :maluca-triage:test

# Local/nightly model regression; ordinary test/build tasks exclude @Tag("llm")
./gradlew :maluca-triage:llmTest
```

The deterministic suite covers anomaly precedence and thresholds, decision
sanitization/pseudonymization, runbook chunking and retrieval for all seven
classes, grounded-output validation, strict patch validation, atomic policy
apply/rollback, and real Flyway migration against the pinned pgvector image.

`llmTest` runs every frozen incident fixture repeatedly at temperature zero
with thinking disabled and bounded time/context settings. It requires
classification + validation + the expected citation + the fixture's scoped
remediation expectation, and compares
the pass rate with `src/test/resources/evals/baseline.json`. It is intentionally
opt-in because it needs a running Ollama model and substantial compute.

## Operational notes

- A `runbook_ingestion_deferred` warning means embedding or another transient
  ingestion dependency was unavailable; the transaction retains the last-good
  chunks, and the ingest endpoint can be called after recovery.
- A `runbook_ingestion_failed_closed` error means `TRIAGE_RUNBOOK_LOCATION`
  discovered no `.md` resources. Correct the location or package the runbooks;
  startup intentionally aborts rather than replacing trusted retrieval with an
  empty corpus.
- Sink drops mean evidence is incomplete, not that proxy traffic failed. Check
  `maluca_sink_queue_size`, `maluca_sink_dropped_total`, and sink failures.
- A fallback report is stored with `valid=false`, `UNKNOWN`, `LOW`, and the
  validation errors; it returns the incident to retry/backoff and eventually
  `TRIAGE_FAILED`, so it must never be presented as completed triage or model
  certainty.
- If apply fails, inspect `audit_events`, the proposal `failure`, and the
  `.bak.<random>` file beside `policies.yml`. `APPLY_FAILED` means compensation
  was verified; `APPLY_INDETERMINATE` means disk/proxy/database state must be
  reconciled against baseline and target digests before any retry.
- Use `ops/TRIAGE_RUNBOOK.md` for incident-control-plane recovery procedures.
