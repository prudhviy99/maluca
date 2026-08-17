# Maluca AI Incident Triage — Implementation Plan

**Status:** completed as-built baseline
**Completed:** 2026-08-13
**Companion requirements:** [`triage-spec.md`](triage-spec.md)

This plan turns the triage specification into an as-built, testable extension
of Maluca. It was written after validating the proposal against the existing
proxy, policy model, build, tests, Docker topology, traffic generators, and
current Spring AI compatibility.

## 1. Outcome

The finished system will:

1. export Maluca decisions through a bounded, non-blocking, failure-isolated
   sink;
2. persist decisions, incident lifecycles, reports, proposals, audit events,
   and pgvector runbook embeddings in PostgreSQL;
3. detect anomalies deterministically, outside the request path;
4. create structured incident reports with local Ollama inference;
5. retrieve runbook context with pgvector and reject unsupported citations;
6. expose bounded operational tools through a separate MCP server;
7. persist gate-valid model proposals with their report, expose post-triage
   proposal creation through MCP, and require authenticated human approval
   before apply;
8. evaluate detection, retrieval, validation, prompts, and model output for
   regression; and
9. provide a reproducible Docker demonstration and complete operator/developer
   documentation.

## 2. Non-negotiable boundaries

- The WebFlux proxy request path contains no LLM, JDBC, file I/O, or blocking
  network call.
- Default `docker compose up` remains useful without PostgreSQL or Ollama.
- Decision export is disabled by default, bounded in memory, and lossy by
  design when the control plane is unavailable.
- Evidence and retrieved text are data, never instructions. Attacker-controlled
  paths are length-capped and delimited in prompts.
- The autonomous triage turn receives six read-only tools. The external agent
  MCP endpoint also exposes proposal creation, but no agent can approve or
  apply.
- Citations and policy patches are validated in Java after generation.
- A failed repair attempt becomes an auditable `UNKNOWN`/`LOW` diagnostic
  report and consumes the bounded retry/backoff budget; it does not falsely
  complete the incident.
- Model-in-loop tests are tagged and excluded from ordinary CI. Deterministic
  detector, retrieval, and safety tests gate every build.

## 3. As-built module design

```text
maluca-contracts     shared decision, incident, report, citation, and patch DTOs
maluca-proxy         existing request path + bounded asynchronous HTTP event sink
maluca-triage        MVC control plane, JDBC/Flyway, detector, RAG, agent, APIs
maluca-mcp           standalone Streamable-HTTP MCP façade over bounded APIs
demo-backend         unchanged protected demonstration service
```

The proxy sends batches to an authenticated internal triage ingest endpoint.
This retains the required PostgreSQL-backed decision history without allowing
datasource auto-configuration or JDBC failure to affect the proxy. The event
sink owns a fixed queue, drops the oldest item on overflow, drains on a
dedicated scheduler, backs off after delivery failure, and records queue,
drop, success, and failure metrics.

The MCP process does not share the triage application's database repository
implementation. It calls the triage and proxy HTTP APIs with strict limits.
This keeps its schemas externally honest and avoids depending on an executable
Spring Boot module.

## 4. Data and lifecycle

Flyway is the only schema owner. Migrations create pgvector and these logical
areas:

- `decisions`: immutable, idempotent events with policy, mode, computed and
  executed actions, evidence contributions, tier, method, trace correlation,
  and a seven-day default retention policy;
- `incidents`: `OPEN`, `TRIAGING`, terminal/manual `TRIAGE_FAILED`, `TRIAGED`,
  `APPROVED`, `APPLIED`, `RESOLVED`, `DISMISSED`, `APPLY_FAILED`, or
  `APPLY_INDETERMINATE`, with
  versioning, fenced claim leases, attempt counts, and retry eligibility;
- `triage_reports`: prompt/model versions, structured output, raw output,
  citations, evidence, and validation failure details;
- `policy_proposals` and `audit_events`: immutable human/agent activity; and
- `runbook_chunks`: Spring AI-compatible content/metadata/embedding columns,
  768-dimensional vectors, stable chunk IDs, and content hashes.

Detection groups on bounded `policy_name`; raw paths remain sampled evidence.
A PostgreSQL advisory lock plus a partial unique index prevents duplicate open
incidents across multiple detector instances.

## 5. Detection and classification

The detector polls a sliding current window and trailing baseline. Rules cover:

- mitigation-share or absolute-volume spikes;
- challenge/block surges;
- credential-stuffing patterns on sensitive policies;
- path enumeration;
- distributed and low-and-slow traffic;
- Redis degradation decisions/metrics; and
- false-positive waves.

Supported classifications are `BURST_FLOOD`, `DISTRIBUTED_FLOOD`, `PATH_SCAN`,
`CREDENTIAL_STUFFING`, `LOW_AND_SLOW`, `REDIS_DEGRADATION`,
`FALSE_POSITIVE_WAVE`, and `UNKNOWN`.

Opening an incident snapshots compact, bounded aggregates and stratified
samples. Normalization closes an incident as `RESOLVED`; a later recurrence
opens a new incident rather than mutating history.

## 6. Retrieval and generation

Runbooks use consistent headings: Symptoms, Confirm, Remediate,
False-positive checks, and Rollback. Ingestion chunks by heading, computes a
content hash, records embedding-model identity, and re-embeds when either the
text or model identity changes (`nomic-embed-text`, 768 dimensions by default).
Replacement is advisory-lock serialized and transactional. Readiness is
fail-closed for an empty/fresh corpus, while a transient dependency failure may
retain a stored last-good corpus.

For an incident, the agent:

1. builds a bounded incident brief;
2. performs top-k cosine retrieval with a minimum similarity;
3. prompts local Ollama through Spring AI with untrusted evidence and trusted
   runbooks explicitly separated;
4. optionally invokes a call-budgeted allowlist of read-only MCP tools within
   an overall orchestration deadline;
5. parses a structured result;
6. validates classification, summary bounds, cited chunk membership and
   metadata, evidence references, and route-scoped patch semantics;
7. retries once with validation feedback; and
8. persists either the valid result or an honest fallback.

## 7. Policy safety and approval

Policy proposals are typed deltas, not arbitrary YAML or shell/file commands.
Validation enforces:

- exact incident policy/route scope;
- supported policy fields only;
- monotonic bands in the range 0–100;
- algorithm-specific positive limits, rates, windows, and bursts;
- valid modes, CIDRs, names, and route patterns; and
- no silent global/default-policy expansion.

Apply is an authenticated human operation. It is bound to the exact proposal
UUID, canonical proposal digest, incident version, and baseline policy digest.
It uses compare-and-swap, writes a same-directory temporary file, keeps a backup,
atomically replaces the policy file, asks the proxy to reload, verifies the
active policy, and restores the backup if verification fails. Verified
rollback becomes `APPLY_FAILED`; uncertainty becomes `APPLY_INDETERMINATE`,
and stranded approved transitions are digest-reconciled. Manual indeterminate
reconciliation derives its result from exact live baseline/target digests, and
terminal triage failure can be dismissed only by operator CAS. Every state
change receives an audit record.

## 8. MCP tool boundary

The standalone Streamable-HTTP server exposes:

- `get_incidents`
- `get_decisions`
- `get_signal_breakdown`
- `query_metrics`
- `list_policies`
- `search_runbooks`
- `propose_policy_patch`

All filters, time ranges, output sizes, and timeouts are bounded. Raw SQL,
filesystem, shell, and arbitrary URL tools do not exist. A human-only
`approve_and_apply` tool is conditional, disabled by default, and published
only on the separately authenticated `/operator/mcp` server. It is never
discoverable from or present in the agent client's `/mcp` tool set.

## 9. Verification matrix

| Layer | Required proof |
|---|---|
| Existing proxy | all current tests remain green |
| Sink | overflow/drop-oldest, unreachable receiver, recovery, idempotency, shutdown |
| Schema | migrations and pgvector index work in Testcontainers |
| Detector | thresholds, zero baseline, debounce, concurrent pollers, close/reopen |
| RAG | ingestion idempotency, changed-content update, expected source in top-k |
| Gate | malformed JSON, forged/empty citation, invented evidence, unsafe patch |
| Prompt safety | injected path/tool text cannot alter instructions or gain privileges |
| MCP | protocol discovery, schemas, auth, limits, and no apply tool for agent |
| Apply | authorization, compare-and-swap, atomic backup, reload verification, rollback |
| Model eval | tagged Ollama fixture matrix, repeated score, committed baseline metadata |
| Packaging | Gradle build, Compose validation, health checks, documented demo |

## 10. Delivery phases

Each phase ends with a green build and documentation update.

1. **Foundation:** contracts, dependency alignment, Flyway schema, module
   skeletons, and configuration reference.
2. **Decision history:** proxy sink, ingest endpoint, metrics, retention, and
   failure tests.
3. **Deterministic incidents:** repository, detector rules, lifecycle, stats,
   and REST API.
4. **Grounded local AI:** runbooks, ingestion, pgvector search, Ollama agent,
   validation/repair, and Markdown reports.
5. **Operational tools:** separate MCP server, bounded adapters, authentication,
   and agent-as-MCP-client wiring.
6. **Human remediation:** typed proposals, strict policy validation, approval,
   atomic apply/reload/verify/rollback, and audit trail.
7. **Regression and packaging:** frozen fixtures, deterministic and LLM tasks,
   Docker overlay, CI, dashboards/runbooks, and final as-built guide.

## 11. Completion criteria

Implementation is complete only when source, migrations, runbooks, tests,
Docker configuration, API/MCP contracts, security controls, evaluation
workflow, and the detailed as-built project guide are present; `./gradlew
clean check` succeeds; normal CI does not require Ollama; and all remaining
environment-dependent verification is explicitly documented with commands and
expected results.
