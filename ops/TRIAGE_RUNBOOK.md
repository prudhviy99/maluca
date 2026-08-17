# Maluca AI Triage Operations Runbook

This runbook operates the PostgreSQL/pgvector, Ollama, deterministic detector,
RAG, MCP, and human approval plane added around Maluca. It complements
[`RUNBOOK.md`](RUNBOOK.md), which covers the proxy hot path, Redis, challenges,
and ordinary mitigation operations.

The control plane may fail without taking the proxy request path down. Preserve
that boundary during incidents: do not add synchronous database, Ollama, or MCP
work to the proxy, and do not restart a healthy proxy merely because triage is
unavailable.

## 1. Safety and authority boundaries

- The triage agent can read bounded evidence, retrieve trusted runbooks, and
  propose a typed policy patch. It cannot approve or apply a patch.
- A human apply call must bind the exact proposal UUID, canonical proposal
  SHA-256, proposal-time baseline policy SHA-256, and current incident version.
  Never infer the proposal by recency or copy fields from a model response.
- Policy apply must remain: validate, write a same-directory temporary file,
  keep a backup, atomically replace, reload, verify active policy, and restore
  the backup if reload/verification fails.
- `X-Maluca-Internal-Token` authenticates proxy ingest and MCP's fixed triage
  read/proposal calls; it cannot apply. Both services read it from
  `MALUCA_INTERNAL_TOKEN`. Do not give this upstream credential to MCP clients.
- Human triage API calls use `Authorization: Bearer $TRIAGE_API_TOKEN`. MCP
  agent clients use `MCP_API_TOKEN`; human MCP apply clients use the distinct
  `MCP_APPLY_TOKEN` at `/operator/mcp`. The proxy admin reload endpoint uses
  `X-Maluca-Admin-Token`; these credentials are not interchangeable.
- Never paste tokens into tickets, reports, prompts, command output, or source
  control. Disable shell tracing before authenticated commands.
- Request paths, client keys, reasons, model output, and MCP arguments are
  untrusted data. They cannot expand tool permissions or select arbitrary SQL,
  files, commands, or URLs.

## 2. Topology and normal health

| Component | Default address | Healthy evidence | Failure impact |
|---|---|---|---|
| Maluca proxy | `http://localhost:8080` | `/actuator/health` is `UP` | Protected traffic is affected; use `RUNBOOK.md` |
| Redis | `localhost:6379` | `redis-cli PING` returns `PONG`; proxy breaker is closed | Per-policy fail-open/fail-closed degradation |
| Triage API | `http://localhost:8082` | `/actuator/health` is `UP` | Ingest/detection/reports stop; proxy remains serving |
| MCP server | `http://localhost:8083` | `/actuator/health` is `UP` | MCP tools unavailable; no authority should be bypassed |
| PostgreSQL/pgvector | Compose `postgres` service | `pg_isready` succeeds and `vector` extension exists | Triage/MCP data operations fail; sink queues then drops oldest |
| Ollama | `http://localhost:11434` | `/api/tags` lists chat and embedding models | New embeddings/reports fail; stored incidents remain queryable |
| Prometheus | `http://localhost:9090` with observability profile | `/-/healthy` succeeds | Global Redis-error detection is unknown and MCP metric confirmation is incomplete; per-policy decision-reason detection continues |

Run all Compose examples from the repository root. Validate and start the
triage overlay with:

```bash
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage config --quiet
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage up -d --build
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage ps
```

Do not use development default secrets outside an isolated workstation. Before
testing authenticated APIs, check only that credentials exist, without printing
their values:

```bash
test -n "${TRIAGE_API_TOKEN:-}" && echo "triage token is set"
test -n "${MCP_API_TOKEN:-}" && echo "MCP token is set"
test -n "${MALUCA_ADMIN_TOKEN:-}" && echo "proxy admin token is set"
```

Baseline health sweep:

```bash
curl -fsS http://localhost:8080/actuator/health | jq .
curl -fsS http://localhost:8082/actuator/health | jq .
curl -fsS http://localhost:8083/actuator/health | jq .
curl -fsS http://localhost:8080/actuator/prometheus >/dev/null
curl -fsS http://localhost:8082/actuator/prometheus >/dev/null
curl -fsS http://localhost:8083/actuator/prometheus >/dev/null
curl -fsS http://localhost:11434/api/tags | jq '.models[].name'
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage exec postgres pg_isready -U maluca -d maluca
```

If the overlay supplies different database credentials, use its
`POSTGRES_USER` and `POSTGRES_DB` values rather than placing a password on the
command line.

The three `/actuator/prometheus` endpoints are intentionally unauthenticated
for the checked-in static scrape topology. Treat their service-network
restriction as mandatory in production; public scraping is not authorization
to expose ports 8080, 8082, or 8083 to the internet.

## 3. First response for a triage incident

1. Record incident ID, opening time, affected policy/route, current status,
   and whether customer traffic is impaired.
2. Check proxy, Redis, triage, PostgreSQL, Ollama, and MCP health. A dependency
   failure can create misleading or incomplete evidence.
3. Fetch bounded incident data; never start with an unbounded database dump:

   ```bash
   curl -fsS \
     -H "Authorization: Bearer ${TRIAGE_API_TOKEN}" \
     'http://localhost:8082/api/v1/incidents?status=OPEN&limit=20' | jq .
   ```

4. Trigger or retry triage only after dependencies are healthy:

   ```bash
   INCIDENT_ID='replace-with-incident-uuid'
   curl -fsS -X POST \
     -H "Authorization: Bearer ${TRIAGE_API_TOKEN}" \
     "http://localhost:8082/api/v1/incidents/${INCIDENT_ID}/triage" | jq .
   ```

5. Verify the report cites only returned runbook chunks and references concrete
   stored evidence. `UNKNOWN`/`LOW` is a safe fallback, not authorization to
   improvise a change.
6. If a proposal exists, follow Section 10. Do not apply directly from a model
   response, Markdown report, or MCP conversation.

## 4. PostgreSQL and pgvector

### Diagnose

Flyway owns the schema and runs when the triage application boots. Confirm the
server, migration history through V4, vector/pgcrypto extensions, V3 proposal
digest columns, V4 lease columns, and bounded table counts:

```bash
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage exec postgres psql -U maluca -d maluca -c \
  "select current_database(), current_user, version();"
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage exec postgres psql -U maluca -d maluca -c \
  "select extname, extversion from pg_extension where extname in ('vector','pgcrypto') order by extname;"
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage exec postgres psql -U maluca -d maluca -c \
  "select installed_rank, version, success from flyway_schema_history order by installed_rank;"
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage exec postgres psql -U maluca -d maluca -c \
  "select (select count(*) from decisions) decisions, (select count(*) from incidents) incidents, (select count(*) from runbook_chunks) chunks;"
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage exec postgres psql -U maluca -d maluca -c \
  "select column_name from information_schema.columns where table_name='policy_proposals' and column_name in ('proposal_sha256','policy_sha256','target_policy_sha256') order by column_name;"
```

Then inspect recent service logs without dumping decision contents:

```bash
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage logs --since=15m postgres maluca-triage
```

Common causes are a full volume, bad credentials, a migration from a newer
binary followed by an older binary, a missing `vector` extension, connection
exhaustion, or an embedding dimension mismatch. Do not repair Flyway history by
editing `flyway_schema_history`. Restore the matching application/schema
version or ship a reviewed forward migration.

### Recover

1. Preserve logs and take a database backup if the server is readable.
2. Restore storage/connectivity and make `pg_isready` pass.
3. Start PostgreSQL before triage; let Flyway complete once. Do not run multiple
   ad-hoc schema owners.
4. Check row counts and runbook dimensions, then start MCP.
5. The proxy sink retries delivery from its bounded in-memory queue. Events
   already counted as dropped cannot be reconstructed from PostgreSQL; record
   the evidence gap in affected incidents.

Restart only the failed control-plane services:

```bash
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage restart postgres
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage restart maluca-triage maluca-mcp
```

After a database restart, repeat the extension/migration checks before
re-running triage. A restart is not a fix for a failed migration or full disk.

## 5. Ollama inference and embeddings

Maluca uses local inference only. There is no silent cloud fallback. The
configured chat model and the 768-dimensional embedding model must be present:

```bash
curl -fsS http://localhost:11434/api/tags | jq '.models[].name'
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage exec ollama ollama list
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage exec ollama ollama ps
```

The project defaults are `qwen3:14b` for chat and `nomic-embed-text` for
embeddings. If a required model is absent, pulling it is a deliberate network
and disk mutation; perform it through the deployment's approved image/init
process. For a local authorized recovery:

```bash
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage exec ollama ollama pull qwen3:14b
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage exec ollama ollama pull nomic-embed-text
```

For NVIDIA deployments, `ollama ps` should report the expected GPU processor.
CPU inference is functionally valid but may make the detector/report queue
appear stalled. On out-of-memory or timeout errors, stop duplicate inference
work, verify the configured model/context/concurrency, and restart Ollama only
after the load is controlled:

```bash
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage restart ollama
curl -fsS http://localhost:11434/api/tags | jq .
```

Do not switch models as an incident shortcut. A model or prompt change requires
the model-in-loop regression evaluation and a recorded baseline update.

## 6. Decision ingest queue

The proxy exports `DecisionEvent` batches to
`POST /internal/v1/decisions` on triage. Export is disabled by default outside
the triage profile, bounded, non-blocking, and drop-oldest under pressure. A
slow/down triage service must never block a protected request.

### Diagnose

1. Confirm both proxy and triage are healthy and that their internal-token
   values match. A 401 indicates a token mismatch; do not disable internal
   authentication to clear it.
2. Inspect the proxy's `maluca_sink_queue_size`,
   `maluca_sink_success_total` (delivered events),
   `maluca_sink_failure_total` (failed batch attempts), and
   `maluca_sink_dropped_total` (queue/shutdown losses), plus
   `maluca_sink_permanent_dropped_total` (events rejected by a permanent 4xx):

   ```bash
   curl -fsS http://localhost:8080/actuator/prometheus | \
     rg '^maluca_(decision_)?sink_'
   ```

3. Inspect bounded logs for delivery status and triage ingest rejection:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.triage.yml \
     --profile triage logs --since=15m maluca-proxy maluca-triage
   ```

4. Confirm database growth over a short controlled interval rather than
   querying event bodies:

   ```sql
   select date_trunc('minute', occurred_at) as minute, count(*)
   from decisions
   where occurred_at >= now() - interval '10 minutes'
   group by 1 order by 1;
   ```

### Recover

- Restore triage/PostgreSQL first. The sink backs off and resumes; avoid
  repeatedly restarting the proxy, because its queue is in memory.
- HTTP 408 and 429 are retryable. Other 4xx responses permanently discard the
  complete rejected batch so later evidence can progress. There is no 413
  batch bisection; correct the proxy batch/triage maximum mismatch and record
  the permanent-drop counter and interval as an evidence gap.
- If the queue is full, dropping the oldest event is expected. Record the drop
  counter and time range as an evidence gap; do not claim complete incident
  sampling.
- A duplicate retry is safe because event IDs are idempotent at ingest.
- If bad events are rejected, fix the producer/contract version. Do not bypass
  validation or insert arbitrary JSON directly into PostgreSQL.
- When triage is intentionally offline for a long maintenance window, either
  accept documented lossy export or disable the sink through a reviewed proxy
  deployment. Do not make the queue unbounded.

## 7. Deterministic detector and incident lifecycle

The detector polls bounded current/baseline windows and creates incidents
without an LLM. PostgreSQL locking and uniqueness prevent duplicate open
incidents. Normalization resolves an incident; recurrence creates a new record
rather than rewriting history.

Expected lifecycle is `OPEN` → `TRIAGING` → `TRIAGED` → `APPROVED` → `APPLIED`,
or `RESOLVED`/`DISMISSED`; a safely compensated application is
`APPLY_FAILED`, while uncertain external state is `APPLY_INDETERMINATE`. An
infrastructure failure or validation-exhausted fallback report returns to
`OPEN` with capped exponential backoff. After the configured attempt budget,
`TRIAGE_FAILED` is a terminal/manual-review state and remains the active
incident for that policy.

Diagnose detector problems with the API, logs, and compact database state:

```bash
curl -fsS \
  -H "Authorization: Bearer ${TRIAGE_API_TOKEN}" \
  'http://localhost:8082/api/v1/incidents?limit=50' | jq .
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage logs --since=30m maluca-triage
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage exec postgres psql -U maluca -d maluca -c \
  "select id, opened_at, policy_name, trigger, status, version, triage_attempts, triage_claimed_at, triage_next_attempt_at, triage_failure from incidents order by opened_at desc limit 20;"
```

At startup and before each scheduled claim, triage reclaims `TRIAGING` leases
older than `TRIAGE_AGENT_LEASE_TIMEOUT`. Do not update lease columns manually:
their UUID fences late report writers. For a reviewed `TRIAGE_FAILED` row, fix
the recorded dependency/input cause and invoke the normal authenticated
`POST /api/v1/incidents/{id}/triage` endpoint. That explicit action deliberately
bypasses backoff, obtains a new lease, and remains subject to the same output
validation.

If the terminal incident is obsolete or intentionally superseded, close it
through the operator-only CAS endpoint instead of editing SQL. Re-fetch the
incident immediately before dismissal and provide an auditable reason:

```bash
EXPECTED_INCIDENT_VERSION="$(curl -fsS \
  -H "Authorization: Bearer ${TRIAGE_API_TOKEN}" \
  "http://localhost:8082/api/v1/incidents/${INCIDENT_ID}" | jq -er '.version')"
jq -n --argjson version "${EXPECTED_INCIDENT_VERSION}" \
  --arg reason 'superseded after dependency recovery and operator review' \
  '{expectedIncidentVersion:$version,reason:$reason}' | \
curl -fsS -X POST \
  -H "Authorization: Bearer ${TRIAGE_API_TOKEN}" \
  -H 'Content-Type: application/json' --data-binary @- \
  "http://localhost:8082/api/v1/incidents/${INCIDENT_ID}/dismiss" | jq .
```

If no incident opens, verify fresh decisions exist, clocks agree, the detector
is enabled, the absolute floor is met, and the current window differs from the
trailing baseline. If duplicates appear, do not manually delete them; preserve
evidence and inspect advisory-lock/partial-index failures. If incidents never
resolve, verify fresh normal windows and the configured normalization duration.

For Redis degradation, also verify the bounded instant query
`sum(increase(maluca_redis_errors_total[60s]))`. A threshold hit opens the
global synthetic policy `__maluca_redis__`; exported `redis_down%` reasons
continue to trigger per-policy fallback windows. If Prometheus is unavailable,
the global signal is unknown and an existing synthetic Redis incident is kept
open rather than falsely resolved.

After fixing inputs, let scheduled polling resume. Use the explicit triage POST
only to generate/retry a report for an existing incident; it does not replace
deterministic incident detection.

## 8. RAG corpus, ingestion, and citation failures

Only reviewed files under `docs/runbooks/` are trusted remediation context.
There are seven sources with five H2 chunks each (`Symptoms`, `Confirm`,
`Remediate`, `False-positive checks`, and `Rollback`), so a complete clean
ingest contains 35 logical source/heading chunks. Content hashes make repeated
ingestion idempotent and changed sections are re-embedded.

Trigger ingestion through the authenticated triage API:

```bash
curl -fsS -X POST \
  -H "Authorization: Bearer ${TRIAGE_API_TOKEN}" \
  http://localhost:8082/api/v1/runbooks/ingest | jq .
```

Verify metadata, dimensions, and duplicates in PostgreSQL:

```sql
select count(*) as chunks from runbook_chunks;
select metadata->>'source' as source, metadata->>'heading' as heading, count(*)
from runbook_chunks
group by metadata->>'source', metadata->>'heading'
having count(*) <> 1;
select vector_dims(embedding) as dimensions, count(*)
from runbook_chunks
group by 1;
```

Exercise retrieval with an incident-like query, not a single label:

```bash
curl -fsS -G \
  -H "Authorization: Bearer ${TRIAGE_API_TOKEN}" \
  --data-urlencode 'query=login failures with sensitive_60s and limit_exceeded on the login policy' \
  --data-urlencode 'k=6' \
  http://localhost:8082/api/v1/runbooks/search | jq .
```

If retrieval is empty or irrelevant:

1. Confirm Ollama's embedding model is available and the configured dimension
   is 768.
2. Confirm all 35 chunks have non-null embeddings plus source, heading,
   checksum, and `embedding_model` metadata.
3. Check the similarity floor and top-k settings; do not disable the floor to
   force unrelated guidance into a prompt.
4. Re-ingest only after the embedding dependency is healthy. A model change
   requires re-embedding the entire corpus and rerunning deterministic
   retrieval evaluations.

Every report citation must match a chunk in that report's retrieved set,
including chunk ID and metadata. The validator retries generation once. A
second invalid citation, unsafe patch, malformed response, or invented evidence
persists an auditable `UNKNOWN`/`LOW` fallback, but returns the incident to
automatic backoff rather than `TRIAGED`. After the attempt budget it becomes
`TRIAGE_FAILED`; fix the corpus/model/input and explicitly retriage then. Never
edit citations directly in the database.

## 9. MCP service and tool failures

The standalone server on port 8083 exposes bounded operational tools over
Streamable HTTP. Normal agent/client capabilities are:

- `get_incidents`
- `get_decisions`
- `get_signal_breakdown`
- `query_metrics`
- `list_policies`
- `search_runbooks`
- `propose_policy_patch`

`/mcp` is the agent server and always exposes only those seven tools. The human
apply capability is disabled by default; when enabled, `/operator/mcp`
requires `MCP_APPLY_TOKEN` and exposes only `approve_and_apply`. The tool is
never registered on `/mcp`, so an agent credential cannot discover it. There
are no raw SQL, arbitrary URL, filesystem, or shell tools.

Check service health and logs first:

```bash
curl -fsS http://localhost:8083/actuator/health | jq .
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage logs --since=15m maluca-mcp
```

Use an MCP client that supports Streamable HTTP to test initialization and
tool discovery at `/mcp` with `Authorization: Bearer $MCP_API_TOKEN`. A plain
GET is not a valid protocol health check. When apply is enabled, separately
connect an operator client to `/operator/mcp`; it must discover exactly
`approve_and_apply`. Disconnect immediately if the agent endpoint advertises
apply or the operator endpoint advertises read/proposal tools.

Failure handling:

- `401/403`: correct the client-specific bearer token/role; do not reuse the
  internal ingest or proxy admin token.
- Timeout or `502/503`: check the bounded downstream adapter (triage, proxy,
  or Prometheus). Keep its time range and result limit small.
- Tool validation error: fix the structured arguments. Never add a raw-query
  escape hatch.
- Truncated result: narrow time range/filter. Do not raise limits globally
  during an incident.
- MCP unavailable during triage: preserve the brief and failure. Do not grant
  the model direct database or policy-file access as a workaround.

After recovery, rediscover tools, run one bounded read call, then test a
proposal against a non-production fixture. Tool availability does not prove
approval isolation; verify that separately.

## 10. Proposal, approval, apply, and rollback

### Review and approve

A proposal is a typed `PolicyPatch`: exact policy name/route plus optional
mode, keying, rate-limit, bands, list deltas, fail mode, and rationale. Unknown
fields, arbitrary YAML, file paths, and commands are invalid.

Policy apply is disabled by default. Enabling
`TRIAGE_POLICY_APPLY_ENABLED=true` is a reviewed deployment decision; it does
not remove bearer authorization, optimistic checks, backup, reload, or
verification requirements.

Before approval, verify:

1. incident evidence and retrieved citations support the change;
2. proposal policy name and route exactly match the incident scope;
3. bands are monotonic and 0–100;
4. limiter values are positive and fields match the algorithm;
5. CIDRs, mode, keying, and fail mode are valid with understood customer risk;
6. exact proposal UUID and canonical proposal SHA-256 match what was reviewed;
7. baseline policy SHA-256 and current incident version still match the
   reviewed state;
8. an on-call owner, success measure, expiry, and rollback are recorded.

Use the read endpoints introduced with digest-bound approvals to fetch the
exact proposal and current incident immediately before applying. List results
help humans choose, but the apply call must name one UUID explicitly:

```bash
INCIDENT_ID='replace-with-incident-uuid'
PROPOSAL_ID='replace-with-reviewed-proposal-uuid'
APPROVED_BY='replace-with-human-identity'

curl -fsS \
  -H "Authorization: Bearer ${TRIAGE_API_TOKEN}" \
  "http://localhost:8082/api/v1/incidents/${INCIDENT_ID}/proposals?limit=20" | \
  jq '[.[] | {id,status,proposalSha256,policySha256,targetPolicySha256,createdAt,createdBy}]'

PROPOSAL_JSON="$(curl -fsS \
  -H "Authorization: Bearer ${TRIAGE_API_TOKEN}" \
  "http://localhost:8082/api/v1/proposals/${PROPOSAL_ID}")"
INCIDENT_JSON="$(curl -fsS \
  -H "Authorization: Bearer ${TRIAGE_API_TOKEN}" \
  "http://localhost:8082/api/v1/incidents/${INCIDENT_ID}")"

printf '%s' "${PROPOSAL_JSON}" | jq -e \
  --arg proposalId "${PROPOSAL_ID}" --arg incidentId "${INCIDENT_ID}" \
  '.id == $proposalId and .incidentId == $incidentId and .status == "PROPOSED"
   and (.proposalSha256 | test("^[0-9a-f]{64}$"))
   and (.policySha256 | test("^[0-9A-Fa-f]{64}$"))' >/dev/null

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
curl -fsS -X POST \
  -H "Authorization: Bearer ${TRIAGE_API_TOKEN}" \
  -H 'Content-Type: application/json' \
  --data-binary @- \
  "http://localhost:8082/api/v1/incidents/${INCIDENT_ID}/apply" | jq .
```

Before mutation, the service computes and durably stores the exact target
policy digest while transitioning to `APPROVED`. It then creates the backup,
atomically writes, calls proxy reload, and verifies the active policy before
returning `APPLIED`. Independently verify:

```bash
curl -fsS \
  -H "X-Maluca-Admin-Token: ${MALUCA_ADMIN_TOKEN}" \
  http://localhost:8080/_maluca/admin/policies | jq .
```

### Apply failures

- `401/403`: human authorization failed. Do not retry with another system's
  token.
- `409`: proposal ID/digest, baseline policy hash, incident version/state, or
  cluster lock is stale/conflicting. Fetch the exact proposal and incident;
  regenerate/review when needed rather than editing request fields to fit.
- Validation rejection: correct the proposal and repeat review; never hand-edit
  the serialized patch after approval.
- File/atomic-move failure: keep the last-known-good file active, check mount
  writability and same-filesystem temporary placement, then retry through the
  service.
- Proxy reload failure: the registry keeps its previous in-memory policy. The
  apply service restores and reloads the backup; a verified compensation is
  `APPLY_FAILED`.
- Verification mismatch: treat as failed even if reload returned 200; restore
  backup, reload it, and investigate route resolution/serialization.
- Database/audit finalization failure after a verified apply: the service
  compensates only while the active file still equals the approved target
  digest. Verified compensation is `APPLY_FAILED`; failed compensation is
  `APPLY_INDETERMINATE`.
- `APPLY_INDETERMINATE`: freeze further applies. Compare the current file,
  proxy projection, exact backup, audit trail, and proposal baseline/target
  digests under an incident owner. Do not blindly retry or manually label it
  `APPLIED`.

The 30-second reconciler handles proposals stranded in `APPROVED` by a crash:
under the cluster advisory lock it finalizes an already-present target, applies
an unchanged baseline, or marks a third digest `APPLY_INDETERMINATE`. It does
not auto-retry rows already marked indeterminate. Inspect bounded state with:

```bash
curl -fsS \
  -H "Authorization: Bearer ${TRIAGE_API_TOKEN}" \
  "http://localhost:8082/api/v1/proposals/${PROPOSAL_ID}" | \
  jq '{id,incidentId,status,proposalSha256,policySha256,targetPolicySha256,approvedAt,appliedAt,failure}'
```

Do not change an incident row to `APPLIED` manually.

When an incident/proposal is already `APPLY_INDETERMINATE`, use the
operator-only deterministic reconciliation endpoint after reviewing all four
bindings. The request expresses no desired outcome; triage compares the live
file with the recorded baseline and target, reloads/verifies the matching
state, and derives `TARGET_CONFIRMED` or `BASELINE_CONFIRMED`:

```bash
PROPOSAL_JSON="$(curl -fsS \
  -H "Authorization: Bearer ${TRIAGE_API_TOKEN}" \
  "http://localhost:8082/api/v1/proposals/${PROPOSAL_ID}")"
INCIDENT_JSON="$(curl -fsS \
  -H "Authorization: Bearer ${TRIAGE_API_TOKEN}" \
  "http://localhost:8082/api/v1/incidents/${INCIDENT_ID}")"

jq -n \
  --arg proposalId "${PROPOSAL_ID}" \
  --arg proposalSha "$(printf '%s' "${PROPOSAL_JSON}" | jq -er '.proposalSha256')" \
  --arg baselineSha "$(printf '%s' "${PROPOSAL_JSON}" | jq -er '.policySha256')" \
  --arg targetSha "$(printf '%s' "${PROPOSAL_JSON}" | jq -er '.targetPolicySha256')" \
  --argjson version "$(printf '%s' "${INCIDENT_JSON}" | jq -er '.version')" \
  '{proposalId:$proposalId,
    expectedProposalSha256:$proposalSha,
    expectedBaselinePolicySha256:$baselineSha,
    expectedTargetPolicySha256:$targetSha,
    expectedIncidentVersion:$version}' | \
curl -fsS -X POST \
  -H "Authorization: Bearer ${TRIAGE_API_TOKEN}" \
  -H 'Content-Type: application/json' --data-binary @- \
  "http://localhost:8082/api/v1/incidents/${INCIDENT_ID}/reconcile-policy" | jq .
```

If the current file matches neither digest, the endpoint returns a conflict,
leaves the state indeterminate, and records `POLICY_RECONCILIATION_REFUSED`.
Investigate the third-party file change; never alter a supplied digest to make
the request pass.

### Manual emergency rollback

There is no general rollback API. Prefer a newly reviewed inverse proposal. If
customer harm requires break-glass restoration before that is possible, two
authorized operators should identify the exact backup from the apply audit
record, verify its SHA-256, restore it atomically, and reload:

```bash
curl -fsS -X POST \
  -H "X-Maluca-Admin-Token: ${MALUCA_ADMIN_TOKEN}" \
  http://localhost:8080/_maluca/admin/policies/reload | jq .
curl -fsS \
  -H "X-Maluca-Admin-Token: ${MALUCA_ADMIN_TOKEN}" \
  http://localhost:8080/_maluca/admin/policies | jq .
```

The commands above reload and verify; they do not select or copy a backup.
Backup restoration is deployment-specific and destructive to the current
policy file, so never use a wildcard or an unverified “latest” file. Record the
break-glass actor, exact source/destination hashes, reload response, active
policy verification, and incident status reconciliation.

## 11. Backups and disaster recovery

Database backups contain client keys, paths, evidence, prompts, and reports.
Store them only in the approved encrypted backup system with access controls
and expiry matching policy.

For an authorized local backup example:

```bash
umask 077
mkdir -p backups
BACKUP_FILE="backups/maluca-triage-$(date -u +%Y%m%dT%H%M%SZ).dump"
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage exec -T postgres pg_dump -U maluca -d maluca -Fc > "${BACKUP_FILE}"
sha256sum "${BACKUP_FILE}"
```

Test a backup by restoring into a new, explicitly named non-production
database, never over the live database:

```bash
RESTORE_CHECK_DB='maluca_restore_check_20260812'
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage exec postgres createdb -U maluca "${RESTORE_CHECK_DB}"
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage exec -T postgres pg_restore -U maluca \
  --exit-on-error -d "${RESTORE_CHECK_DB}" < "${BACKUP_FILE}"
docker compose -f docker-compose.yml -f docker-compose.triage.yml \
  --profile triage exec postgres psql -U maluca -d "${RESTORE_CHECK_DB}" \
  -c 'select count(*) from incidents;'
```

Dropping the restore-check database or replacing production data is
destructive and requires explicit authorization and an exact target. Prefer
switching the application to a newly restored database after validation over
restoring in place.

Recovery order after total control-plane loss:

1. Keep the independently healthy proxy and Redis serving.
2. Recover PostgreSQL and validate Flyway/vector state.
3. Recover Ollama and both configured models.
4. Start triage; verify health, ingest authentication, detector polling, and
   runbook count.
5. Start MCP and verify the role-specific tool list.
6. Let the proxy sink drain; document any dropped-event interval.
7. Re-ingest the reviewed runbook corpus if embeddings were not restored.
8. Explicitly retriage open incidents whose report generation failed.
9. Reconcile proposals/audit state before permitting any apply.

## 12. Privacy, retention, and evidence handling

- Decision retention defaults to seven days. Change it only through reviewed
  configuration, and monitor the scheduled prune job. Do not hand-delete rows
  to resolve capacity without retention-owner approval and a verified target.
- Client keys are pseudonymous identifiers, not anonymous data. Paths can
  contain tenant/account information. Apply least privilege to database,
  reports, MCP clients, logs, and backups.
- Decision export must not include request bodies, passwords, cookies,
  authorization headers, challenge tokens, or raw API keys. Keep this invariant
  when extending `DecisionEvent`.
- Client-key pseudonymization is enabled by default. Protect
  `TRIAGE_CLIENT_HMAC_KEY` as a secret and keep it stable during an incident;
  rotating it intentionally breaks correlation between old and new client
  keys and must be recorded as an evidence boundary.
- Ingest truncates paths to the configured maximum (512 characters by
  default), but path segments can still carry identifiers. Truncation is a
  storage bound, not anonymization.
- Store only bounded sampled rows in incident evidence. Metrics use bounded
  policy/action labels, never client keys or raw paths.
- Triage reports must cite runbook chunks and immutable evidence references;
  they must not reproduce unnecessary client data.
- `raw_response` exists for debugging failed validation and can contain echoed
  evidence. Give it the same or stricter access and retention as decisions.
- Runbook changes are code-reviewed trusted-input changes. Do not ingest ticket
  text, request paths, model output, or arbitrary operator uploads into the
  trusted corpus.
- Expire database dumps, local fixtures, and exported reports under the same
  policy as their most sensitive source. Deleting primary rows while retaining
  indefinite dumps does not satisfy retention.

Capacity symptoms from retention failure are rising database volume, slow
detector queries, old BRIN ranges, and delayed ingest. Verify the scheduled job
and indexes, then use an approved retention repair. Vacuum/maintenance should
follow PostgreSQL operational policy; it is not a substitute for pruning.

## 13. Final recovery verification

Do not close a control-plane incident until all applicable checks pass:

- proxy request health and Redis breaker state are correct;
- triage and MCP actuator health are `UP`;
- PostgreSQL is ready, Flyway is successful, and pgvector dimensions match;
- both Ollama models exist and one controlled inference succeeds;
- decision rows are arriving and sink queue/failure counters have stabilized;
- detector polling advances without duplicate open incidents;
- the RAG corpus has 35 unique source/heading chunks and a known query retrieves
  the expected source;
- an intentionally invalid citation/unsafe patch is rejected by the gate;
- MCP discovery exposes only the role's allowlisted tools;
- active proxy policy matches the expected SHA/version after any apply or
  rollback;
- every data gap, manual action, approval, backup, and rollback is recorded in
  the incident/audit trail.
