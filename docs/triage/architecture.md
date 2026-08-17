# AI Incident Triage: As-Built Architecture

This document describes the code implemented in `maluca-contracts`,
`maluca-proxy`, `maluca-triage`, and `maluca-mcp` as of 2026-08-13. It is an
as-built reference, not the earlier design proposal in
[`../triage-spec.md`](../triage-spec.md).

## System boundary

The triage system is a control plane around Maluca's existing WebFlux proxy. It
does not put PostgreSQL, Ollama, vector search, or MCP calls in the protected
request's synchronous decision path.

```text
                                     read/propose tools
                              +----------------------------+
                              |                            v
client -> maluca-proxy -> upstream                 maluca-mcp :8083
             |                                      |    |    |
             | bounded in-memory queue              |    |    +-> Prometheus
             | asynchronous HTTP batches            |    +------> proxy admin API
             v                                      +-----------> triage API
       POST /internal/v1/decisions                         ^
             |                                             |
             v                                             |
      maluca-triage :8082 <-------------------------------+
       |      |       |
       |      |       +-> Ollama chat and embeddings :11434
       |      +----------> repository-owned runbooks
       +-----------------> PostgreSQL 16 + pgvector
```

The default failure posture preserves that separation:

- decision export is disabled unless explicitly enabled on the proxy;
- a full export queue discards the oldest queued event rather than waiting;
- retryable failed batches retry off the request thread with capped exponential
  backoff, while permanent 4xx rejections are dropped and counted;
- startup runbook ingestion retains a stored last-good corpus across a
  transient Ollama failure, while a fresh database remains not ready;
- a detector or agent failure does not change proxy request handling; and
- policy application is disabled by default and requires a separate human
  approval operation.

## Module responsibilities

| Module | Implemented responsibility |
|---|---|
| `maluca-contracts` | Shared Java records and enums for decision batches, incidents, reports, citations, runbook results, and typed policy patches. It contains no Spring application. |
| `maluca-proxy` | Existing mitigation request path plus `DecisionEventFactory` and the optional asynchronous `DecisionSink`; also returns structured active-policy state for reload verification. |
| `maluca-triage` | Spring MVC/virtual-thread control plane, Flyway schema owner, authenticated ingest/query APIs, deterministic detector, pgvector runbook ingestion/search, Ollama triage worker, validation gate, reports, proposals, and apply workflow. |
| `maluca-mcp` | Stateless Spring AI Streamable-HTTP MCP façade over bounded triage, proxy, and Prometheus clients. It owns no incident data. |
| `docs/runbooks` | Seven repository-reviewed Markdown sources copied into the triage artifact at build time and chunked at H2 headings. |

All modules target Java 21. The root build currently manages Spring Boot
3.5.15 and Spring AI 1.1.8.

## Decision export flow

After `MitigationWebFilter` has selected a decision, it records normal proxy
metrics/logs and, when the sink is enabled, constructs one `DecisionEvent`.
This also covers allowlist, denylist, Redis-degraded, and valid pass-cookie
bypass outcomes. Requests under `/actuator` and `/_maluca` bypass the mitigation
filter and are therefore not exported.

`DecisionEventFactory` captures:

- a random event UUID and UTC occurrence time;
- the resolved composite client key;
- method and raw path, without the query string;
- matched policy name, route, mode, and client tier;
- computed and executed actions;
- score, decision reason, and score-contribution map;
- the dry-run flag; and
- the current trace ID when one exists.

For a dry-run or observe-mode decision, `computedAction` retains the action the
pipeline selected while `executedAction` is `ALLOW`. For unmatched policy
traffic, policy name and route are the literal string `none`.

The proxy refuses a policy reload when any name or route is blank, a name is
longer than 128 characters, a route is longer than 512 characters, or names are
duplicated. These are the same identity bounds enforced by triage ingest. The
registry compiles before its atomic swap, so any such failure leaves the last
good policy set active.

`DecisionSink.offer` performs only bounded deque operations. A single virtual
thread drains up to 500 events by default, either when the threshold is reached
or the one-second flush interval elapses. It serializes a `DecisionBatch` and
POSTs it to the configured triage endpoint with
`X-Maluca-Internal-Token`. HTTP 408/429, transport failures, and non-2xx
responses outside the 4xx range leave the batch in the worker's in-flight
variable for retry. Other 4xx responses are permanent: the complete batch is
discarded without bisection, its event count is recorded in
`maluca_sink_permanent_dropped_total`, and the worker advances to later queued
evidence. New events can continue to enter the bounded queue and evict its
oldest entries while a retryable failure is in progress. Graceful shutdown gets
a bounded opportunity to flush; remaining in-flight and queued events are
counted as ordinary drops.

The triage ingest service rejects batches above 500 by default, validates UUID,
timestamp, action, score, and required text fields, truncates the path, and
HMAC-pseudonymizes the client key by default. PostgreSQL uses `event_id` as the
primary key and ignores duplicate deliveries.

## Deterministic incident detection

`AnomalyDetector` is scheduled only when
`maluca.triage.detection.enabled=true`. Every poll runs in a transaction,
attempts a transaction-scoped PostgreSQL advisory lock, and computes one
aggregate per `policy_name` over:

- a current window of 60 seconds by default; and
- the immediately preceding 15-minute baseline.

Mitigated and challenge/block counts use **computed** actions, so a `DRY_RUN` or
`OBSERVE` policy can still generate an incident without enforcing the action.
Before evaluating those per-policy windows, triage makes one bounded Prometheus
instant query for
`sum(increase(maluca_redis_errors_total[<current-window>]))`. When that global
increase reaches the configured threshold (one by default), the detector adds
a synthetic `__maluca_redis__` window with route `/_maluca/redis`. The regular
decision aggregation remains a fallback: exported reasons beginning with
`redis_down` increment each affected policy's `redisErrors`, including when
Prometheus is unavailable. The evaluator uses this fixed priority:

1. `REDIS_DEGRADATION` when a window's Redis-error count reaches the configured
   threshold, whether supplied by the global Prometheus window or decision
   reasons;
2. `CHALLENGE_BLOCK_SURGE` when computed `CHALLENGE` plus `BLOCK` count reaches
   20;
3. `MITIGATION_SPIKE` when mitigated count is at least 30, mitigation share is
   at least 0.25, and it is at least three times baseline share (or baseline is
   zero); and
4. `TRAFFIC_VOLUME_SURGE` when total count is at least 100 and four times the
   baseline volume normalized to the current-window length (or baseline is
   zero).

Prometheus failure returns an unknown signal rather than a false zero. The
detector still evaluates decision-reason windows, but it does not auto-resolve
an existing synthetic `__maluca_redis__` incident until Prometheus becomes
readable again. MCP's `query_metrics` tool remains available for bounded human
or agent confirmation.

One partial unique index permits at most one active incident per policy name.
The opening poll freezes the JSON stats used for the eventual model brief.
When a matching anomaly continues, `openOrTouch` refreshes only
`last_active_at`; it deliberately does not churn the reviewed optimistic
version or replace the opening stats. Once an eligible `OPEN`, `TRIAGED`, or
`APPLIED` incident has not been
active for the configured five minutes and no current rule matches, the
detector changes it to `RESOLVED` and sets `closed_at`. Transitional/manual
`TRIAGING`, `TRIAGE_FAILED`, `APPROVED`, and `APPLY_INDETERMINATE` rows are not
auto-resolved.

## Runbook ingestion and retrieval

The triage Gradle build copies `docs/runbooks/*.md` into the executable
artifact under `runbooks/`. There are seven trusted sources:

- `burst-flood.md`
- `distributed-flood.md`
- `path-scan.md`
- `credential-stuffing.md`
- `low-and-slow.md`
- `redis-degradation.md`
- `false-positive-wave.md`

`RunbookChunker` creates one chunk for every non-empty H2 section. The current
corpus has five uniform sections per file, producing 35 chunks. Each chunk has
a stable logical ID of `<source>#<heading-slug>`, includes the document title
and section heading in its embedded text, and carries a SHA-256 content hash.
Fixed resource, Markdown, heading, per-chunk, per-file-section, and whole-corpus
limits reject oversized or duplicate stable IDs before embedding.

`RunbookIngestionService` runs at startup by default and can also be invoked
through `POST /api/v1/runbooks/ingest`. It:

1. discovers and chunks the classpath resources, rejecting a corpus with zero
   Markdown files;
2. checks the embedding model's reported dimension against the configured 768;
3. opens one database transaction and takes a transaction-scoped PostgreSQL
   advisory lock, serializing ingestion across replicas;
4. reads existing `chunk_id`, hash, source, embedding-model, and physical UUID
   metadata;
5. leaves hash- and model-identical chunks unchanged;
6. upserts changed chunks under their existing physical IDs and new chunks
   under deterministic name-based UUIDs; and
7. only after those upserts succeed, deletes stored Markdown chunks absent
   from the desired corpus and commits the complete replacement.

Embedding and storage are delegated to Spring AI's `VectorStore`. Retrieval
uses cosine similarity, defaults to top six with a 0.45 floor, caps a request
at 12 results, and rejects blank or greater-than-8,000-character queries.

The replacement is atomic: embedding/upsert/delete failures roll back instead
of exposing a partially replaced corpus. At startup, a transient Ollama or
network failure is deferred; readiness remains `UP` only for a complete stored
corpus whose embedding-model identity, trusted marker, checksum, bounded
metadata, and bounded content all pass validation, while a fresh
database remains `OUT_OF_SERVICE` and the agent will not claim incidents. An
empty/malformed configured corpus or another permanent ingestion failure fails
startup closed. Manual ingestion and search surface dependency errors to the
caller. Last-good readiness additionally requires every stored row's
`embedding_model` to match current configuration, and retrieved rows must carry
bounded source/heading/chunk metadata plus `trusted=true`.

## Triage generation flow

The scheduled worker polls every ten seconds by default. PostgreSQL
`FOR UPDATE SKIP LOCKED` atomically moves the oldest currently eligible `OPEN`
incident to `TRIAGING`, assigns a random lease UUID and claim timestamp, and
increments its attempt count. A future `triage_next_attempt_at` excludes a
failed incident from the queue so another incident can proceed. Manual
`POST /api/v1/incidents/{id}/triage` bypasses that delay and can claim either
`OPEN` or terminal `TRIAGE_FAILED` state.

At application readiness and before each scheduled claim, the worker fences
`TRIAGING` leases older than 15 minutes by default. It returns them to `OPEN`
with capped exponential backoff or moves them to `TRIAGE_FAILED` at the third
attempt. Report persistence first locks and verifies the exact lease UUID in a
database transaction; a late worker therefore cannot overwrite the report
produced under a newer lease. `TRIAGE_FAILED` stays active for the policy and
requires an explicit authorized retry rather than being reclaimed forever.

For a claimed incident, the worker:

1. loads up to 50 decisions for the incident policy and stats window;
2. deterministically orders them newest-first, emits only selected operational
   fields, retains each sample's highest contributions, and serializes them
   inside an `<untrusted_incident_evidence>` envelope. Defaults cap each sample
   at 1,200 characters and the complete brief at 16,000 characters;
3. builds a deterministic focused retrieval query from trigger, policy, route,
   aggregate counts, action counts, and top contributions, then retrieves
   matching runbook chunks. The complete brief remains available to the model
   for grounding but is not used as the vector query;
4. prompts the configured Ollama chat model with a fixed system prompt,
   untrusted evidence, separately delimited trusted context, and a generated
   JSON schema for `TriageResult`;
5. attaches only remotely discovered MCP callbacks whose names occur in the
   configured allowlist and enforces a per-attempt tool-call budget;
6. parses the reply with Spring AI's `BeanOutputConverter`;
7. runs the Java validation gate; and
8. makes one repair attempt by default when parsing or validation fails.

The complete retrieval/model/tool/repair sequence runs inside a bounded
orchestration task. The 4-minute default deadline is longer than the 90-second
Ollama HTTP timeout and shorter than the 15-minute claim lease. Model thinking
is disabled so a reasoning stream cannot contaminate strict JSON output.

The validation gate enforces required classification/confidence/summary,
150-word summary length, at most 12 evidence entries, exact normalized
field/value-pair presence in the brief (including bounded one-level nested map
paths), exact citation membership and metadata, no duplicate citations, at
least one citation for a non-`UNKNOWN` classification, and route-scoped patch
semantics.

The dedicated Ollama API client bounds both connect and read at 90 seconds by
default, and Spring AI transport retries are capped at two. After the single
semantic repair, safe finalization removes only evidence items that still lack
an exact frozen pair and re-runs the complete gate. If an otherwise valid
diagnosis contains an invalid optional patch, it removes that patch and again
re-runs the complete gate; application code never invents replacement policy
values. The raw response is retained for audit. If no grounded result survives,
the agent creates an `UNKNOWN`/`LOW` fallback with no evidence, citations, or
patch. That fallback does **not** complete triage: the fenced claim returns to
`OPEN` with capped exponential backoff. A later attempt replaces the one-report
projection; the third failed claim by default moves the incident to
`TRIAGE_FAILED` for an explicit authorized manual retry. Infrastructure failures
use the same bounded retry/manual-review path.

One report row exists per incident. A repeated save replaces its report fields
while preserving the existing report ID. The exact retrieved chunk views used
for the attempt—including content
and similarity—are stored in `retrieval_context` and returned as
`retrievedChunks` by the JSON report API. The raw model response is stored for
debugging but omitted from `TriageReportView`; the Markdown endpoint renders
accepted evidence and citations but not the raw response or full retrieval
context.

## MCP tool boundary

`maluca-mcp` serves two physically separate Spring AI Streamable HTTP servers
on port 8083. `/mcp` always uses the agent provider and exposes exactly:

- `get_incidents`
- `get_decisions`
- `get_signal_breakdown`
- `query_metrics`
- `list_policies`
- `search_runbooks`
- `propose_policy_patch`

The triage process is an MCP client only when
`MALUCA_MCP_CLIENT_ENABLED=true`; it also filters discovered callbacks against
a six-name read-only allowlist that deliberately excludes
`propose_policy_patch`. A validated patch returned in JSON is persisted with
its report transaction instead. External post-triage MCP clients can still use
all seven `/mcp` tools. With MCP disabled or no callbacks discovered,
generation still runs from the incident brief and retrieved chunks.

`approve_and_apply` is never registered with `/mcp`, so an agent credential
cannot discover it even when apply is enabled. When
`MALUCA_MCP_APPLY_ENABLED=true`, a second server appears at `/operator/mcp`; it
requires the distinct operator bearer and publishes only
`approve_and_apply`. Its method also requires `ROLE_OPERATOR`. The in-process
triage client connects only to `/mcp`, and its name allowlist never includes
the apply tool.

MCP calls fixed upstreams through timeout- and response-size-bounded clients.
It does not expose raw SQL, filesystem access, shell execution, or caller-chosen
URLs. MCP does not automatically retry proposal or apply POSTs. A transport
failure after dispatch is reported as indeterminate because the upstream may
already have committed the mutation; the caller must inspect incident,
proposal, and audit state before retrying. See
[`../../maluca-mcp/README.md`](../../maluca-mcp/README.md) for its complete
protocol and PromQL validation contract.

## Proposal and apply flow

A report patch and an apply-ready proposal remain distinct records, but their
provenance is now atomic:

1. `TriageResult.proposedPatch` is optional model output stored in
   `triage_reports`.
2. A gate-valid model patch is validated against the live policy and creates a
   separate `policy_proposals` row in the same fenced transaction as the
   report.
3. `POST /api/v1/proposals` lets an external MCP/operator submit a validated
   patch only after the incident is `TRIAGED` with a current valid report.

Both paths bind the row to the exact report generation using `report_id` plus
`report_created_at`, capture the current policy-file SHA-256, and resolve the
patch against the complete live document before persistence. A later report
generation quarantines older `PROPOSED` rows as
`REJECTED_STALE_PROVENANCE`; approval joins the proposal to the still-current
valid report. Repeated submission of the same report-generation/patch/actor is
idempotent.

Application is off unless `TRIAGE_POLICY_APPLY_ENABLED=true`. Approval binds
four reviewed values: exact `proposalId`, that row's canonical
`proposalSha256`, its baseline policy-file `policySha256`, and the current
incident `version`. The service requires that exact proposal to remain
`PROPOSED` for the path incident, recomputes the stored JSONB patch digest, and
validates the patch again. It never selects a “latest” proposal implicitly.

The service first acquires a connection-pinned, transaction-scoped PostgreSQL
advisory lock around the entire workflow. That cluster-wide lock serializes
all policy application across triage replicas; a competing request fails
before lifecycle state changes. Under the lock it:

1. verifies the baseline file digest and computes the exact patched-file
   `targetPolicySha256` without mutating the file;
2. transactionally moves the incident and exact proposal to `APPROVED`, stores
   the target digest, and writes the approval audit event;
3. compare-and-swaps the on-disk policy against the approved baseline digest;
4. parses the YAML tree and changes only the existing exact name/route match;
5. validates the resulting document;
6. copies a same-directory backup;
7. writes a same-directory temporary file and requires an atomic replacement;
8. asks the proxy to reload and verifies its structured active-policy state;
   and
9. transactionally records the proposal and incident as `APPLIED`.

Pre-approval digest/validation failures leave the proposal `PROPOSED` and the
incident `TRIAGED`. Once durable approval exists, a
mutation/reload/verification failure restores the backup where one was created
and finishes as `APPLY_FAILED`. If database finalization fails after a verified
mutation, the service compensates from the exact backup, but only while the
active file still has the approved target digest. A failed or unverifiable
compensation records both proposal and incident as `APPLY_INDETERMINATE`;
operators must reconcile it rather than retry blindly.

A 30-second scheduled reconciler handles a crash that leaves a proposal in
durable `APPROVED` state. Under the same cluster lock it verifies the stored
patch digest, then either finalizes an already-present target, applies from the
approved baseline, or marks the operation indeterminate when the file matches
neither digest. `APPLY_INDETERMINATE` itself is a manual reconciliation state,
not an automatic retry queue.

For that manual state, operator-only
`POST /api/v1/incidents/{id}/reconcile-policy` requires the exact proposal,
proposal digest, baseline digest, target digest, and incident version. The
caller cannot choose an outcome: verified target state becomes `APPLIED`,
verified baseline becomes `APPLY_FAILED`, and a third digest remains
`APPLY_INDETERMINATE` with a refusal audit. Operator-only
`POST /api/v1/incidents/{id}/dismiss` similarly closes only an exact-version
open `TRIAGE_FAILED` incident and records its bounded reason.

The YAML tree is serialized with the YAML mapper, so formatting and comments
are not preserved. Reload verification checks specified mode, keying, fail
mode, bands, list membership changes, and the rate-limit algorithm together
with specified `limit`, `windowSeconds`, `ratePerSecond`, and `burst` values.

## HTTP surface

All triage routes except public actuator health/info/Prometheus require authentication.
Most application routes accept either the operator bearer token or the
internal token described in [`security.md`](security.md); the apply route
accepts only the operator bearer.

| Method and path | Behavior |
|---|---|
| `POST /internal/v1/decisions` | Validate and idempotently ingest a decision batch; returns HTTP 202 with inserted/duplicate counts. |
| `GET /api/v1/decisions` | Query newest decisions by optional policy, pseudonymous client key, computed action, time bounds, and a 1–200 clamped limit. |
| `GET /api/v1/signals` | Sum contribution values for one policy over a positive window no larger than 24 hours; default window is 15 minutes. |
| `GET /api/v1/incidents` | List up to 100 recent incidents, optionally by exact status. |
| `GET /api/v1/incidents/{id}` | Return one incident. |
| `POST /api/v1/incidents/{id}/triage` | Synchronously claim an `OPEN` or `TRIAGE_FAILED` incident for explicit triage. |
| `GET /api/v1/incidents/{id}/report` | Return the externally safe JSON report. |
| `GET /api/v1/incidents/{id}/report.md` | Render the report as `text/markdown`. |
| `GET /api/v1/runbooks/search?query=...&k=...` | Search trusted chunks; `limit` is accepted as an alias for `k`. |
| `POST /api/v1/runbooks/ingest` | Run synchronized checksum-aware ingestion. |
| `POST /api/v1/proposals` | Store a validated proposal for a `TRIAGED` incident with a current valid report. |
| `GET /api/v1/proposals/{id}` | Read one exact proposal, including proposal/baseline/target digests and status. |
| `GET /api/v1/incidents/{id}/proposals?limit=20` | List that incident's proposals newest-first, with a 1–100 clamped limit. |
| `POST /api/v1/incidents/{id}/apply` | Operator-only exact-proposal approval/application with proposal ID/digest, baseline file digest, and incident version. |
| `POST /api/v1/incidents/{id}/reconcile-policy` | Operator-only digest-derived reconciliation of `APPLY_INDETERMINATE`. |
| `POST /api/v1/incidents/{id}/dismiss` | Operator-only CAS dismissal of an open `TRIAGE_FAILED` incident with a reason. |

`IllegalArgumentException` maps to 400, missing rows to 404, and
`IllegalStateException` to 409. Other runtime/dependency failures use Spring's
normal server-error handling.

## Observability

The implementation publishes these dedicated Micrometer instruments in
addition to normal Spring/JVM metrics:

| Prometheus name | Meaning |
|---|---|
| `maluca_sink_queue_size` | Current proxy export queue depth. |
| `maluca_sink_dropped_total` | Events evicted or abandoned during sink shutdown. |
| `maluca_sink_permanent_dropped_total` | Events discarded after one permanent 4xx ingest rejection. |
| `maluca_sink_success_total` | Events successfully delivered, not batch count. |
| `maluca_sink_failure_total` | Failed batch delivery attempts. |
| `maluca_triage_ingest_accepted_total` | Newly inserted decision rows. |
| `maluca_triage_ingest_duplicates_total` | Duplicate event IDs ignored. |
| `maluca_triage_incidents_opened_total` | Newly opened incidents. |
| `maluca_triage_incidents_resolved_total` | Detector-resolved incidents. |
| `maluca_triage_agent_reports_total{result="valid|fallback"}` | Model outcomes from scheduled or explicit attempts; fallback means stored rejected output followed by retry/manual-review state. |
| `maluca_triage_agent_claims_total{result="deferred|manual_review|lease_reclaimed|stale_result"}` | Bounded retry and fencing outcomes. |

The proxy, triage, and MCP `/actuator/prometheus` scrape endpoints are public
for the checked-in static Prometheus topology. Triage health/info and MCP
health are also public; triage metrics/Flyway and MCP info remain protected.
Production deployments must enforce scrape-network access at the service mesh,
firewall, or reverse proxy.

## Current implementation limits

- There is no browser/chat UI; interfaces are REST, Markdown reports, MCP, SQL
  owned by the service, metrics, and tests.
- Apart from the global Redis-error Prometheus rule, the detector uses exported
  decisions; it does not ingest arbitrary upstream application events.
- Classification remains model-driven after a deterministic trigger; the Java
  gate checks grounding/shape, not whether a classification logically follows
  from a trigger.
- There is no API to create a `MANUAL` incident or list audit events. Terminal
  triage dismissal and indeterminate-policy reconciliation are operator-only
  APIs; proposal get/list endpoints also exist.
- Decision retention is implemented; automatic retention for incidents,
  reports, proposals, audits, raw model responses, and embeddings is not.
- A policy patch can modify only an existing policy. It cannot add a new route.
- MCP availability is optional and does not determine whether local RAG and
  Ollama triage can run.
