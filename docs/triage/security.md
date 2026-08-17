# AI Incident Triage: Security Model

This document records the controls that exist in code. It does not claim that
the local development defaults are production-safe.

## Security objectives

The implementation is designed around five boundaries:

1. AI and control-plane dependencies do not participate in proxy request
   availability.
2. Attacker-influenced decision evidence is data, never trusted instructions.
3. Runbook text is repository-owned trusted context, not arbitrary uploaded
   text.
4. Model output cannot directly become an arbitrary file edit or command.
5. Proposal and application are separate operations; policy application is
   off by default and requires human-authorized credentials and optimistic
   state.

The system still relies on deployment controls for TLS, network segmentation,
secret storage, PostgreSQL access control, Ollama isolation, backup encryption,
and host filesystem permissions.

## Trust zones and data flows

| Zone | Trusted for | Not trusted for |
|---|---|---|
| Proxy request/evidence | Describing what Maluca observed | Instructions, URLs, commands, identity claims, or remediation authority |
| `docs/runbooks` in the built artifact | Reviewed operational context | Dynamic incident evidence or user uploads |
| Ollama output | Candidate structured analysis | Citations, facts, patches, approval, or executable instructions until Java validation |
| MCP agent credential | Bounded reads and proposal submission | Human approval/application |
| MCP apply credential / triage operator token | Invoking the approval endpoint when enabled | Bypassing hash/version/patch validation or filesystem/reload verification |
| Internal service token | Proxy/MCP-to-triage service calls | Public client authentication; it is broad inside the triage API and must stay internal |
| Proxy admin token | Policy list/reload on the proxy | Triage or MCP client authentication |

No cloud model fallback is configured. Chat and embeddings use the configured
Ollama base URL.

## Triage HTTP authentication

Triage uses a stateless Spring Security filter. CSRF, server sessions, and
cookie authentication are not part of this API. Tokens are compared with
`MessageDigest.isEqual` and are not logged by the filter.

Two credentials map to fixed roles:

| Credential | Header | Principal/role | Effective access |
|---|---|---|---|
| `TRIAGE_API_TOKEN` | `Authorization: Bearer ...` | `maluca-operator`, `ROLE_OPERATOR` | All `/api/**`, apply when enabled, and protected metrics/Flyway actuator endpoints. |
| `MALUCA_INTERNAL_TOKEN` | `X-Maluca-Internal-Token: ...` | `maluca-service`, `ROLE_INTERNAL` | `/internal/**` and non-apply `/api/**`; not apply or protected actuator endpoints. |

The internal header is evaluated before the bearer header. If both are
present and the internal token matches, the request has only `ROLE_INTERNAL`.

Endpoint authorization is:

| Path | Authorization |
|---|---|
| `/actuator/health`, `/actuator/info`, `/actuator/prometheus` | Public. Metrics are exposed for the configured Prometheus scrape; restrict service ports at the network edge. Health details use Spring Boot's `when_authorized` setting. |
| `/internal/**` | `ROLE_INTERNAL` only. |
| `/api/v1/incidents/*/apply` | `ROLE_OPERATOR` only, plus the runtime apply feature flag. |
| Other `/api/**` | `ROLE_OPERATOR` or `ROLE_INTERNAL`. |
| Other exposed `/actuator/**` | `ROLE_OPERATOR` only. |
| Everything else | Denied. |

Missing/invalid authentication receives the configured JSON 401 entry point;
an authenticated but unauthorized role uses Spring Security's normal forbidden
handling.

The proxy's `/actuator/prometheus` endpoint is likewise unauthenticated. Across
proxy, triage, and MCP, public means “reachable by the configured scraper,” not
safe for internet exposure; restrict these listener ports at the network edge.

The internal role is deliberately broad because the proxy and MCP server use it
for fixed ingest/read/proposal calls. It cannot approve or apply. Its token must
still be limited to the service network; it is not a public or read-only
credential.

## MCP authentication and apply isolation

MCP uses a separate stateless bearer-token filter on port 8083:

- `MCP_API_TOKEN` receives `ROLE_AGENT`.
- `MCP_APPLY_TOKEN` receives both `ROLE_AGENT` and `ROLE_OPERATOR`, but only
  while `MALUCA_MCP_APPLY_ENABLED=true`.
- `/actuator/health`, health component paths, and `/actuator/prometheus` are
  public for the static scrape topology; other MCP/actuator requests require
  at least `ROLE_AGENT`.
- Tokens are fixed strings compared in constant time and the security context
  is cleared after every request.

The server refuses to create the apply provider when the apply token is blank
or equals the agent token. With a blank agent token, protected endpoints fail
closed.

MCP uses physical discovery separation, not only method authorization. `/mcp`
is backed by `agentToolProvider` and always contains the seven bounded
read/propose tools only. `approve_and_apply` is never registered there. When
apply is enabled, `/operator/mcp` is created with the separate operator server
and publishes only `approve_and_apply`; the route requires `ROLE_OPERATOR` and
the method repeats that requirement with `@PreAuthorize`. An agent credential
therefore cannot discover the apply tool. The triage client connects only to
`/mcp`, and its six-name read-only allowlist excludes both proposal and apply,
providing an additional fail-closed layer. Validated model patches are stored
with the fenced report transaction instead.

MCP does not forward its client bearer token. Its fixed upstream credentials
are:

- `MALUCA_INTERNAL_TOKEN` as `X-Maluca-Internal-Token` for triage
  read/proposal calls;
- `TRIAGE_API_TOKEN` as an operator bearer for the apply call only;
- `MALUCA_ADMIN_TOKEN` as `X-Maluca-Admin-Token` to the proxy; and
- optional `PROMETHEUS_BEARER_TOKEN` as a bearer token to Prometheus.

The JDK HTTP clients do not follow redirects, which prevents fixed credentials
from being redirected to a different origin. Base URLs must be absolute
HTTP(S) origins without user info, query, fragment, or non-root path.

## Ingest validation and minimization

The proxy exports an intentionally narrow event. It does not include request
bodies, cookies, authorization headers, passwords, challenge tokens, or raw API
keys.

Before persistence, triage:

- rejects a batch above the configured maximum;
- requires event ID and occurrence time;
- rejects a timestamp more than five minutes in the future;
- accepts only the six Maluca action names for computed/executed action;
- requires a score from 0 through 100;
- requires client key, method, path, policy name/route/mode, tier, and reason;
- uppercases and allowlists the three policy modes;
- bounds client key, policy name/route, tier, reason, and optional trace ID;
- accepts at most 64 contribution entries with bounded keys and finite values
  in `0..1,000,000,000`;
- truncates method to 16 characters and path to the configured bound; and
- pseudonymizes the client key by default.

Pseudonymization uses HMAC-SHA-256 and exposes only its first 20 bytes. It is
stable and non-reversible without the configured key, but it remains personal
or correlatable operational data. The path, tier, trace ID, timing, policy, and
behavioral pattern can also be sensitive.

`DecisionEvent` ignores unknown JSON fields. This supports compatible producer
evolution but means adding an unexpected wire field is not itself rejected;
only fields represented by the record are stored.

## Prompt-injection controls

The agent receives incident and decision JSON inside an explicit
`<untrusted_incident_evidence>` envelope containing a warning not to follow
embedded instructions, commands, URLs, or role changes. The system prompt
repeats that evidence and tool results are untrusted and separates retrieved
text inside `<trusted_runbook_context>`.

The brief is not a serialization of the complete persistence records. Java
selects the operational incident/sample fields, orders samples and equal-score
contributions deterministically, removes trace/event identifiers, retains only
the highest contributions, and enforces both a per-sample and whole-brief
character ceiling. Control characters in selected strings are replaced before
JSON encoding. These bounds limit context exhaustion by attacker-controlled
paths, reasons, and signal names.

Additional boundaries are code-enforced:

- there is no runbook upload endpoint; ingestion reads only the
  operator-configured Spring resource pattern, whose checked-in default selects
  repository-built runbooks;
- runbook search rejects blank/oversized queries and bounds result count;
- remotely discovered tools are filtered through an exact configured name
  allowlist;
- MCP validates strings, enums, timestamps, windows, result sizes, policy
  shapes, and PromQL before I/O;
- MCP clients use fixed upstream origins and methods;
- response buffering, connection time, and read time are bounded; and
- upstream non-2xx, oversized, invalid-JSON, timeout, and semantically invalid
  responses become sanitized tool errors rather than empty success results.

Mutation POSTs are not automatically retried. A transport failure after
dispatch becomes an indeterminate-operation error so a caller must reconcile
incident, proposal, and audit state before deciding whether to retry.

These controls reduce impact; they do not make a model a security boundary.
Java validation remains authoritative.

The local Ollama API uses an explicit connect/read timeout (90 seconds by
default), Spring AI HTTP retries are capped, and the complete
retrieval/tool/model/repair orchestration has a separate 4-minute deadline.
Each attempt also has a maximum MCP tool-call count. Worker claim leases are
larger than that total deadline and carry a UUID fencing token. Expired results
cannot write a report after another replica reclaims the incident.

## Output grounding gate

The model must produce the strict `TriageResult` shape. The gate rejects:

- missing classification, confidence, or summary;
- a summary over 150 words by default;
- more than 12 evidence references;
- null/blank evidence pairs;
- a bounded evidence `(fact, value)` pair that does not occur together in the
  normalized frozen brief;
- a non-`UNKNOWN` result without a citation;
- a citation whose chunk ID was not retrieved;
- mismatched source/heading metadata; and
- duplicate citations.

Every proposed patch is passed through the triage patch validator. A failed
parse/gate result receives one repair attempt by default. Safe finalization may
then remove only evidence without an exact frozen pair and re-run the complete
gate; it may also discard an invalid optional patch from an otherwise valid
diagnosis and re-run the gate, but it never invents replacement policy values.
The raw response remains persisted for diagnosis. If no grounded result
survives, code creates a low-confidence `UNKNOWN` fallback. That fallback does
not make the incident eligible for approval/application or complete it as
`TRIAGED`: the incident returns to `OPEN` with bounded backoff, then moves to
`TRIAGE_FAILED` for explicit manual review after the configured attempt budget.

Grounding has deliberately limited semantics. The gate requires each evidence
fact and value to occur as one selected-field assignment/JSON pair in the
normalized brief, but it does not prove that the summary's causal conclusion
is correct. It does not map
classification to trigger or require a citation from a same-named runbook.
The report persists and returns the entire retrieved chunk snapshot, but that
does not make the model's interpretation correct. Human review remains
required.

## Policy patch validation

Triage is the authoritative route-aware validator. For a non-null patch it
requires:

- `policyName` and `route` exactly equal the incident;
- mode in `ENFORCE`, `OBSERVE`, `DRY_RUN` when supplied;
- keying in `NETWORK`, `COMPOSITE`, `FINGERPRINT` when supplied;
- fail mode in `FAIL_OPEN`, `FAIL_CLOSED` when supplied;
- a supported limiter algorithm;
- positive `limit` and `windowSeconds`, with no bucket fields, for window
  algorithms;
- finite positive `ratePerSecond` and positive `burst`, with no window fields,
  for bucket algorithms;
- resolved band values strictly increasing from 0 through 100;
- IP literals/CIDRs rather than hostnames in list deltas;
- a non-blank rationale no longer than 2,000 characters; and
- at least one supported field or list delta supplied. The validator does not
  load the live policy to prove that a supplied value is different.

Partial band patches are resolved by `PolicyPatchValidator` against configured
triage defaults, not the existing policy's explicit bands. This can reject a
partial patch that would be valid against a particular policy, or allow it to
reach apply based on different starting values. `PolicyFileService` also checks
the ordering of all band keys present in the patched YAML. On reload, the proxy
fills omitted keys from its own global defaults and validates the complete
effective five-band ordering before accepting the new registry snapshot. An
invalid effective combination makes reload fail; triage then restores the
backup and reloads the prior policy. Proposal-time validation can therefore be
conservative or incomplete for partial bands, but apply fails safely.

The triage service is the final validation boundary for every caller. It
enforces algorithm-specific fields, finite positive limiter values with hard
upper bounds, score-band ordering, literal IP/CIDR syntax, per-list and total
network-entry caps, per-list uniqueness, no contradictory additions/removals,
and rationale control-character and length rules. MCP performs compatible
pre-validation and additionally constrains simple policy-name and route shapes
so malformed requests fail before an upstream call. Deployment authorization
must still restrict the direct API to trusted services and operators.

## Human approval and file safety

Policy application has three independent gates:

1. `TRIAGE_POLICY_APPLY_ENABLED` must be true.
2. The caller must hold triage `ROLE_OPERATOR`; an MCP caller must first hold
   the separate MCP operator role and then use the configured dedicated triage
   operator bearer upstream.
3. The request must bind the exact `proposalId`, canonical stored-patch
   `proposalSha256`, proposal-time baseline `policySha256`, and current incident
   version. The proposal must still be `PROPOSED` for that incident, its digest
   is recomputed from stored JSONB, and patch validation must pass again.

Callers should obtain those immutable values from
`GET /api/v1/proposals/{id}` and the current version from
`GET /api/v1/incidents/{id}`. The service never infers a “latest” proposal.
Before changing lifecycle state it verifies the baseline file and computes the
exact target-content digest. The approval transaction stores that
`target_policy_sha256` while moving the exact proposal and incident to
`APPROVED`.

After those request gates, `PolicyApplyLock` holds a PostgreSQL transaction
advisory lock on one pinned connection across the file mutation, proxy reload,
verification, and rollback window. Only one triage replica can mutate the
shared policy file at a time; a competing request fails before the lifecycle
transition. Rolling back the otherwise read-only lock transaction releases it.

The displayed `approvedBy` string does not create authority. The audit actor is
the authenticated principal, optionally suffixed with a truncated supplied
name.

`PolicyFileService` operates on one configured normalized absolute path.
Proposal-time hashing requires a regular readable file. Apply additionally
requires the file and parent directory to be writable, finds an existing exact
name/route pair, parses route patterns, verifies unique policy names and
strictly increasing present bands, and writes through a temporary file in the
same directory. If the filesystem cannot perform an atomic move, application
fails.

Before replacement it creates a collision-resistant same-directory backup and
verifies its digest. After a
successful proxy reload it checks active structured policy state. On apply or
verification failure it restores the backup and asks the proxy to reload it.
If a later database finalization fails after a verified mutation, the service
also compensates from that exact backup, but only if the current file still
matches the approved target digest. A verified compensation records
`APPLY_FAILED`; failed rollback/reload verification or an unexpected current
digest records `APPLY_INDETERMINATE` so operators do not mistake an uncertain
external state for a safe failure.

Current verification is patch-scoped rather than a byte-for-byte comparison.
It checks requested mode, keying, fail mode, specified bands, list membership
deltas, limiter algorithm, and specified numeric
limit/window/rate-per-second/burst values against the proxy's structured active
policy response. Separately, the target SHA-256 binds the exact serialized
policy-file bytes approved for crash reconciliation.

The scheduled reconciler scans durable `APPROVED` proposals. Under the same
cluster lock, it verifies the stored patch digest and compares the current file
with the baseline and target digests: an existing target is reloaded/verified
and finalized, an unchanged baseline is applied, and any third state becomes
`APPLY_INDETERMINATE`. It does not automatically retry an already-indeterminate
operation.

Operator reconciliation is also fail-closed. It requires the exact proposal,
proposal/baseline/target digests, and reviewed incident version, then derives
the result from the live file and proxy instead of accepting a caller-selected
outcome. A third digest remains indeterminate and produces a refusal audit.

YAML comments and formatting are not preserved because the parsed tree is
pretty-printed on write. The backup is the authoritative pre-change artifact.

## Auditability

Proposal, approval, successful apply, failed apply, indeterminate apply,
manual reconciliation/refusal, and terminal triage dismissal create database
audit events. Approval records proposal and target digests;
successful apply records previous/new SHA-256 and backup path. The proposal
stores the full typed patch, its canonical digest, creator, exact report ID and
generation timestamp, proposal-time baseline hash, and approved target hash.
Reports store
model and prompt versions, accepted evidence/citations, the complete retrieved
chunk snapshot, validation errors, and raw model output.

The JSON report projection omits the raw model response. The Markdown renderer
escapes HTML and Markdown control characters from model summaries, evidence
labels, citations, rationales, and validation messages so attacker-influenced
text cannot create active HTML, links, images, or injected report headings.

There is no external audit-query endpoint or MCP audit tool. Database access or
a future bounded API is required to review the audit table. There is also no
implemented audit record for model tool calls, runbook ingestion, report reads,
or ordinary evidence queries.

## Secrets and deployment requirements

The checked-in triage configuration contains convenient non-blank development
defaults for `TRIAGE_API_TOKEN`, `MALUCA_INTERNAL_TOKEN`,
`TRIAGE_CLIENT_HMAC_KEY`, `MCP_API_TOKEN`, `MALUCA_ADMIN_TOKEN`, and the local
database password. Those defaults are unsafe outside an isolated workstation.
Unlike MCP's blank public-facing token default, triage does not fail startup
when its development secrets are in use.

Production deployment must:

- inject unique high-entropy secrets from a secret manager;
- use distinct triage API, internal, MCP agent, MCP apply, proxy admin, HMAC,
  and database credentials;
- terminate TLS or use a private authenticated service mesh, because default
  URLs are plain HTTP;
- restrict 5432, 8082, 8083, 9090, and 11434 to intended principals;
- mount the policy file read-only in proposal-only triage instances and grant
  file-plus-parent write access only to an apply-capable instance;
- run hardened container variants as an unprivileged UID/GID with only the
  required volume permissions; the checked-in Java runtime Dockerfiles do not
  declare `USER` and therefore run as the image default root user;
- keep apply disabled in agent-only deployments;
- encrypt database volumes and backups and apply retention to raw responses;
- avoid logging request headers and environment values; and
- rotate the HMAC key only with an explicit decision-correlation migration or
  documented correlation boundary.

## Known security limitations

- Triage has two coarse roles, not per-endpoint scopes. The internal token can
  read all API evidence and submit post-triage proposals. Apply, dismissal, and
  indeterminate reconciliation are operator-only.
- The API bearer token is an operator token; there is no read-only REST role.
- Fixed tokens have no expiry, audience, issuer, or per-user attribution.
- Public health/info reveal normal Spring application metadata; health details
  are suppressed unless authorized.
- Reports retain raw model output indefinitely unless an external retention
  process is added.
- A model patch is persisted only with a gate-valid report in the fenced report
  transaction. A later report generation quarantines its older pending
  proposals, but historical rows and raw model output remain sensitive data.
- The patch validator uses configured defaults for its preliminary partial-band
  check. Proposal creation additionally resolves the patch against the current
  complete YAML document and validates its final band/list/CIDR state; proxy
  reload verification remains the final compiled-state check.
- The checked-in proxy, triage, and MCP runtime images do not set a non-root
  user.
- Apply spans database state, filesystem mutation, and proxy HTTP calls; it is
  cluster-serialized, target-digest bound, auditable, compensating, and
  reconciled across durable `APPROVED` gaps, not a single ACID transaction.
- Security tests cover the MCP boundary and a focused triage MVC slice for
  terminal lifecycle role mapping; the complete triage HTTP surface does not
  yet have full end-to-end authorization coverage.
