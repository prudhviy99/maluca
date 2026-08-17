# Maluca MCP operations server

`maluca-mcp` is the narrow operations gateway between an incident-triage agent and
Maluca's control-plane services. It exposes Spring AI 1.1.8 tools over the MCP
Streamable-HTTP transport, validates and bounds every request, and keeps policy
application behind a physically separate, opt-in operator endpoint and credential.

The design plan is in [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).

## Architecture

```text
triage agent                                      human operator
MCP_API_TOKEN                                     MCP_APPLY_TOKEN
      |                                                  |
      | GET/POST/DELETE /mcp                             | GET/POST/DELETE /operator/mcp
      v                                                  v
+-------------------------------+       +--------------------------------+
| agent MCP server              |       | operator MCP server (opt-in)   |
| exactly seven safe tools      |       | approve_and_apply only         |
+---------------+---------------+       +----------------+---------------+
                |                                        |
                +--------------------+-------------------+
                                     v
                      maluca-mcp (stateless security)
                          |         |          |
                          v         v          v
                    maluca-triage  proxy   Prometheus
```

This module owns no incident or policy data. It is a synchronous, timeout-bound
adapter over fixed upstream base URLs. Java 21, Spring Boot 3.5.15, and Spring AI
1.1.8 are managed by the root Gradle build.

Readers new to MCP or Maluca's human-approval boundary should start with the
root [beginner guide](../docs/beginner-guide.md#38-mcp-tools-and-authority-separation).

## Agent tools on `/mcp`

| Tool | Mutability | Input summary | Upstream operation |
|---|---|---|---|
| `get_incidents` | Read | Optional status, limit | `GET /api/v1/incidents` |
| `get_decisions` | Read | Optional policy, client key, computed action, UTC range, limit | `GET /api/v1/decisions` |
| `get_signal_breakdown` | Read | Policy and optional UTC range | `GET /api/v1/signals` |
| `query_metrics` | Read | Restricted PromQL, UTC start/end, step seconds | `GET /api/v1/query_range` on Prometheus |
| `list_policies` | Read | None | `GET /_maluca/admin/policies` on the proxy |
| `search_runbooks` | Read | Query and result limit | `GET /api/v1/runbooks/search` |
| `propose_policy_patch` | Writes a proposal only | Incident UUID and typed `PolicyPatch` | `POST /api/v1/proposals` |

These seven tools are the complete contents of `/mcp`. Its `tools/list` response
always advertises exactly these seven names, including when operator apply is
enabled. `approve_and_apply` is neither registered with nor discoverable through
the agent server.

## Operator tool on `/operator/mcp`

When `maluca.mcp.apply-enabled=true`, a second MCP server is mounted at
`/operator/mcp`. It advertises only `approve_and_apply` and requires the operator
bearer credential. When apply is disabled, the operator server and route are not
created.

| Tool | Required input | Upstream operation |
|---|---|---|
| `approve_and_apply` | `incidentId`, exact `proposalId`, `expectedProposalSha256`, `expectedPolicySha256`, `expectedIncidentVersion` | `POST /api/v1/incidents/{incidentId}/apply` |

The operator identity is bound to `MCP_APPLY_TOKEN` by server configuration; it
is not accepted as a tool argument. The separate route, server, tool registry,
bearer role, and method authorization are independent enforcement layers.

## Authentication and authorization

All MCP endpoints and protected actuator endpoints require:

```http
Authorization: Bearer <token>
```

There are two credentials with different authority:

- `MCP_API_TOKEN` receives `ROLE_AGENT`. It can use the seven read/propose tools
  at `/mcp` and protected actuator endpoints, but it cannot access the operator
  server or apply policy changes.
- `MCP_APPLY_TOKEN` receives `ROLE_AGENT` and `ROLE_OPERATOR`, but is reserved for
  the physically separate `/operator/mcp` server and interactive human/operator
  clients when apply mode is enabled. `/operator/mcp` specifically requires
  `ROLE_OPERATOR`.
- `MCP_APPLY_PRINCIPAL` is the audited identity bound by configuration to that
  operator credential. A tool caller cannot supply or spoof it.

Apply mode refuses to start if the apply token is blank or equals the agent token.
Startup also rejects reuse of an inbound MCP credential as the triage internal or
proxy admin credential.
With the default blank API token, protected endpoints fail closed with HTTP 401.
Tokens are compared in constant time and are never logged. Rotate them through the
deployment secret store and restart the service.

Streamable HTTP uses `GET`, `POST`, and `DELETE` on `/mcp` and, when enabled,
`/operator/mcp`. Every method passes through the stateless bearer filter chain.
CSRF is disabled because the API has no cookies or browser session and
authenticates every protected request with a bearer token.

Upstream credentials have separate scopes:

- Calls to `maluca-triage` use `X-Maluca-Internal-Token` from
  `MALUCA_INTERNAL_TOKEN` for evidence retrieval and proposal creation.
- Only `approve_and_apply` uses `Authorization: Bearer` with the dedicated triage
  operator credential from `TRIAGE_API_TOKEN`. The read/propose client never sends
  that credential.
- Policy listing uses the proxy's existing `X-Maluca-Admin-Token` from
  `MALUCA_ADMIN_TOKEN`.
- When configured, Prometheus receives `Authorization: Bearer` using
  `PROMETHEUS_BEARER_TOKEN`.

MCP credentials are not forwarded upstream. Upstream clients do not follow HTTP
redirects, which prevents credentials from being redirected to a different host.
Keep triage, proxy admin, and Prometheus endpoints on a private service network;
the MCP bearer is an edge credential and must never grant direct upstream access.

## Human approval workflow

Apply is disabled by default. The safe workflow is:

1. The agent retrieves incidents, decisions, signal aggregates, current policy,
   metrics, and trusted runbook chunks.
2. After a gate-valid report has moved the incident to `TRIAGED`, an external
   agent calls `propose_policy_patch`. Triage rejects the call without that
   current valid report. A successful call stores a typed proposal bound to the
   report ID and generation timestamp; it cannot reload or mutate the active
   proxy policy. Its response is a review receipt. Triage's proposal read APIs
   return the same receipt for later review.
3. A human reviews the evidence, citations, exact stored patch, blast radius, and
   receipt. Never substitute a newer proposal merely because it belongs to the
   same incident.
4. An operator client authenticated with `MCP_APPLY_TOKEN` connects to
   `/operator/mcp` and calls `approve_and_apply` with these five inputs:

   | Receipt/review value | Exact tool input |
   |---|---|
   | Reviewed incident UUID | `incidentId` |
   | Reviewed proposal UUID | `proposalId` |
   | Stored canonical proposal digest | `expectedProposalSha256` |
   | Policy baseline digest captured with the proposal | `expectedPolicySha256` |
   | Incident version observed during review | `expectedIncidentVersion` |

   The successful proposal response and `GET /api/v1/proposals/{proposalId}` /
   `GET /api/v1/incidents/{incidentId}/proposals` provide the proposal review
   receipt; the incident read supplies the reviewed incident version. Copy these
   values exactly. Do not recompute the canonical proposal digest client-side.
5. Triage performs its own feature-flag, optimistic-version, validation, audit,
   exact-proposal digest, atomic-file, and reload checks. A missing or mismatched
   receipt field fails closed; stale policy/incident versions cannot overwrite
   newer work.

Enable the capability only for a controlled operator deployment:

```bash
export MALUCA_MCP_APPLY_ENABLED=true
export MCP_API_TOKEN='agent-token-from-secret-store'
export MCP_APPLY_TOKEN='different-human-token-from-secret-store'
export MCP_APPLY_PRINCIPAL='oncall-operator@example.test'
export TRIAGE_API_TOKEN='separate-triage-operator-token'
```

No MCP-side retry is performed for proposal or apply POSTs. This avoids silently
replaying a state-changing request; the caller should inspect the audited upstream
state before retrying.
If a connection/read failure happens after dispatch, the tool explicitly reports
the outcome as **indeterminate**: triage may have committed the proposal or apply.
Reconcile the incident/proposal and audit state before retrying. The optimistic
incident version and policy SHA make an already-completed apply retry fail stale,
but proposal creation does not yet have an idempotency-key contract.

### Durable target and application reconciliation

Approval and application are distinct durable phases. After all five reviewed
values match, triage computes the target policy SHA-256 and durably records the
proposal as `APPROVED` with that target before mutating the policy file or
reloading the proxy. `targetPolicySha256` therefore describes the exact approved
result, while `policySha256` remains the reviewed pre-application baseline.

If execution is interrupted after approval, triage reconciles under its
cluster-wide apply lock:

- active policy equals `targetPolicySha256`: verify the stored patch is present
  and finalize the proposal/incident as applied;
- active policy equals the proposal's baseline `policySha256`: apply the exact
  stored proposal and finalize it; or
- active policy matches neither digest: mark the outcome `APPLY_INDETERMINATE`,
  retain the audit evidence, and require operator investigation rather than
  overwriting unknown state.

An MCP timeout is not evidence that application failed. Read the proposal receipt
and incident/audit state until it reaches a terminal state before deciding whether
another operator action is appropriate.

## Compatibility and rollout order

The exact-proposal receipt is a fail-closed contract change. Deploy the contracts,
database migration, and `maluca-triage` first; verify its proposal create/read
responses expose the receipt and its apply endpoint requires all five reviewed
values. Deploy `maluca-mcp` only after that rollout is healthy.

Mixed-version requests are not downgraded to the earlier incident-only approval
shape. Missing receipt fields, an unknown proposal-read route, or a triage apply
endpoint that does not accept the bound request must surface as an error and must
not trigger an alternate apply path. Do not roll triage back to a pre-receipt
version while the new MCP operator endpoint is enabled.

## Bounds and validation

### Evidence and retrieval

- General result limits default to 100 and are capped at 200.
- Runbook retrieval defaults to 8 chunks and is capped at 12.
- Decision and signal evidence windows require both endpoints when supplied and
  are capped at 24 hours.
- Tool strings reject control characters and have field-specific length limits.
- Incident statuses and mitigation actions are parsed against their explicit enum
  allowlists.
- Every upstream body is streamed into a bounded buffer. Default caps are 1 MiB
  for triage/proxy and 2 MiB for Prometheus.
- Connect/read timeouts are set per upstream; redirects are disabled.

### PromQL

`query_metrics` can call only Prometheus's read-only `/api/v1/query_range` API.
The caller cannot provide a URL, path, or HTTP method. The validator requires an
explicit metric in an allowed namespace and enforces:

- a 2,048-character expression cap;
- allowed metric prefixes (`maluca_`, `http_server_`, `jvm_`, `process_`,
  `system_`, plus the exact metric `up` by default);
- no nameless selectors or `__name__` label indirection around that allowlist;
- a conservative function allowlist;
- no `offset`, `@` modifier, regex label matcher, subquery, or many-to-one vector
  join;
- at most 20 metric selectors;
- range-vector selectors no larger than the configured maximum;
- an outer range no larger than 6 hours;
- a whole-second step of at least 15 seconds;
- a Prometheus-side query timeout (5 seconds by default, never longer than the
  HTTP read timeout);
- no more than 5,000 requested or returned samples; and
- no more than 50 returned series.

Prometheus still controls its own query timeout and concurrency. Keep those limits
enabled; this service's validation is an additional boundary, not a replacement.

### Policy proposals

`PolicyPatch` comes from `maluca-contracts` and cannot carry YAML, filesystem paths,
shell commands, or arbitrary keys. MCP additionally validates names/routes, enum
values, algorithm-specific rate fields, non-empty deltas, rate bounds, monotonic
score bands, rationale length, IP/CIDR syntax, duplicates, contradictory
additions/removals, and total network-entry count.
Triage remains authoritative and must validate the resolved patch against the
current policy before persistence or application.

## Configuration

The complete defaults live in `src/main/resources/application.yml`.

| Environment variable | Property | Default | Purpose |
|---|---|---|---|
| `PORT` | `server.port` | `8083` | HTTP listener |
| `MCP_API_TOKEN` | `maluca.mcp.security.bearer-token` | blank/fail closed | Agent/read-propose credential |
| `MCP_APPLY_TOKEN` | `maluca.mcp.security.approval-bearer-token` | blank | Human apply credential |
| `MCP_APPLY_PRINCIPAL` | `maluca.mcp.security.approval-principal` | `maluca-operator` | Audited identity bound to the apply credential |
| `TRIAGE_API_TOKEN` | `maluca.mcp.triage-approval-token` | blank | Dedicated triage operator credential for apply only |
| `MALUCA_MCP_APPLY_ENABLED` | `maluca.mcp.apply-enabled` | `false` | Mount the separate human `/operator/mcp` server |
| `MALUCA_TRIAGE_URL` | `maluca.mcp.triage.base-url` | `http://localhost:8082` | Triage service base URL |
| `MALUCA_INTERNAL_TOKEN` | `maluca.mcp.triage.auth-token` | blank | Triage service credential |
| `MALUCA_PROXY_URL` | `maluca.mcp.proxy.base-url` | `http://localhost:8080` | Proxy admin base URL |
| `MALUCA_ADMIN_TOKEN` | `maluca.mcp.proxy.auth-token` | blank | Proxy policy-list credential |
| `PROMETHEUS_URL` | `maluca.mcp.prometheus.base-url` | `http://localhost:9090` | Prometheus base URL |
| `PROMETHEUS_BEARER_TOKEN` | `maluca.mcp.prometheus.auth-token` | blank | Optional Prometheus credential |

Legacy aliases `MALUCA_MCP_BEARER_TOKEN` and
`MALUCA_MCP_APPROVAL_BEARER_TOKEN` remain fallback inputs, but new deployments
should use `MCP_API_TOKEN` and `MCP_APPLY_TOKEN`.

Each upstream supports `connect-timeout`, `read-timeout`, and
`max-response-bytes`. Limit and PromQL properties are grouped under
`maluca.mcp.limits` and `maluca.mcp.promql`. Configuration properties are startup
validated. Base URLs must be absolute HTTP(S) URLs and cannot contain user info,
query strings, or fragments.

## Run locally

The service requires Java 21. Upstreams need not be available for the process to
start; a tool call reports a sanitized upstream error if its dependency is down.

```bash
export MCP_API_TOKEN='local-agent-token'
export MALUCA_INTERNAL_TOKEN='local-internal-token'
export MALUCA_ADMIN_TOKEN='local-admin-token'

./gradlew :maluca-mcp:bootRun
```

Build the executable jar:

```bash
./gradlew :maluca-mcp:bootJar
java -jar maluca-mcp/build/libs/maluca-mcp-0.1.0-SNAPSHOT.jar
```

Or build the container from the repository root:

```bash
docker build -f maluca-mcp/Dockerfile -t maluca-mcp:local .
docker run --rm -p 8083:8083 \
  -e MCP_API_TOKEN=local-agent-token \
  -e MALUCA_TRIAGE_URL=http://host.docker.internal:8082 \
  -e MALUCA_PROXY_URL=http://host.docker.internal:8080 \
  -e PROMETHEUS_URL=http://host.docker.internal:9090 \
  maluca-mcp:local
```

Health is deliberately public for container and orchestrator probes:

```bash
curl --fail http://localhost:8083/actuator/health
```

`/actuator/prometheus` is public for the internal Prometheus scrape, matching
the proxy deployment pattern; restrict the service port at the network edge.
`/actuator/info` requires the agent or operator bearer token.

## Spring AI MCP client configuration

The triage service can connect with Spring AI's Streamable-HTTP client:

```yaml
spring:
  ai:
    mcp:
      client:
        type: SYNC
        request-timeout: 20s
        streamable-http:
          connections:
            maluca-operations:
              url: http://localhost:8083
              endpoint: /mcp
```

The client's HTTP request customizer must add:

```http
Authorization: Bearer ${MCP_API_TOKEN}
```

Do not configure the triage agent with `MCP_APPLY_TOKEN`. Keep its local tool-name
allowlist restricted to the seven agent-safe names. Operator clients use a
separate Streamable-HTTP connection with endpoint `/operator/mcp` and add
`Authorization: Bearer ${MCP_APPLY_TOKEN}`.

## Error behavior

- Missing/invalid inbound credentials return HTTP 401. The agent endpoint never
  advertises the operator tool, and an agent credential cannot access the
  operator endpoint; no upstream apply call occurs.
- Invalid tool arguments fail before network I/O with a concise validation error.
- Upstream non-2xx responses become sanitized tool failures containing the service
  name and status code, not credentials or response bodies.
- Timeout, connection, invalid-JSON, response-size, Prometheus series, and sample
  violations fail the tool call. Partial/truncated data is never returned.
- The adapter intentionally does not turn an upstream error into an empty result;
  an empty array/object means the upstream actually returned one.

## Upstream API assumptions

The module deliberately parses upstream success bodies as JSON trees for forward
compatibility. Shared request types (`PolicyProposalRequest`, `PolicyPatch`, and
`ApprovalRequest`) are compile-time contracts. These route/query assumptions must
remain stable:

- `GET /api/v1/incidents?status=&limit=` returns a JSON incident array. The triage
  implementation may impose a lower limit than MCP's general cap.
- `GET /api/v1/decisions?policy=&client_key=&action=&from=&to=&limit=` returns a
  JSON decision array. Times are RFC 3339 instants; `action` filters the decision's
  computed action in the current triage repository contract.
- `GET /api/v1/signals?policy=&from=&to=` returns a JSON object containing the
  aggregate signal contribution breakdown.
- `GET /api/v1/runbooks/search?query=&k=` returns source-bearing runbook chunks.
- `POST /api/v1/proposals` accepts `PolicyProposalRequest` and persists but does
  not apply a proposal. Its success response supplies the immutable review
  receipt used for exact-proposal approval.
- `GET /api/v1/proposals/{id}` and
  `GET /api/v1/incidents/{id}/proposals?limit=` return proposal review receipts,
  including proposal, baseline, target, status, and application outcome fields.
- `POST /api/v1/incidents/{id}/apply` accepts `ApprovalRequest`. Triage must allow
  only its operator bearer role on this endpoint and require the exact proposal
  UUID/digest, policy baseline digest, and incident version while retaining its
  own apply flag, validation, reconciliation, and audit checks. The MCP internal
  service credential must not be accepted for apply.
- `GET /_maluca/admin/policies` returns the proxy's compiled active policy view.
- Prometheus implements the standard `GET /api/v1/query_range` response envelope.

There is no assumption that upstream error bodies have a stable shape; they are
not relayed to MCP clients.

## Tests

Run the module suite from the repository root:

```bash
./gradlew :maluca-mcp:test
```

Coverage includes exact agent and operator tool discovery on their physically
separate Streamable-HTTP endpoints, callback schemas and invocation, default
absence of the operator route, fail-closed token configuration, agent-token
denial, human method authorization, public health/protected endpoints, triage
query/header contracts, response byte caps, PromQL request/result limits, and
policy-patch validation.
