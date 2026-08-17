# Maluca MCP implementation plan

## Goal

Build a standalone Spring Boot MVC service that exposes Maluca incident evidence and
safe operational actions through Spring AI 1.1.8's Streamable-HTTP MCP transport.
The service is a bounded control-plane adapter: it does not own incident data,
Prometheus data, or active policy state.

## Trust boundaries

1. Require a configured bearer token for every application and MCP endpoint. Keep
   only `/actuator/health` and its component paths public.
2. Send the internal service token only to `maluca-triage` and the admin token only
   to `maluca-proxy`; never accept an arbitrary upstream URL from a tool argument.
   Use a separate triage operator bearer exclusively for human-approved apply.
3. Permit Prometheus's read-only query APIs only. Validate metric namespaces,
   expression size, time range, step, requested sample count, response size, series
   count, and returned sample count.
4. Register evidence and proposal tools in the default agent provider. Register
   `approve_and_apply` in a separate provider only when `maluca.mcp.apply-enabled`
   is true, and require a distinct human approval bearer token at invocation time.

## Deliverables

- Application/configuration properties and MVC security.
- Bounded `RestClient` adapters for triage, Maluca proxy, and Prometheus.
- MCP tools: `get_incidents`, `get_decisions`, `get_signal_breakdown`,
  `query_metrics`, `list_policies`, `search_runbooks`, and
  `propose_policy_patch`.
- Opt-in, human-authorized `approve_and_apply` tool in a separate provider.
- Unit and MVC tests for callback composition, input/result limits, security, and
  the default absence of apply capability.
- Module README describing architecture, configuration, contracts, security,
  operations, and API assumptions.

## Verification

Run `:maluca-mcp:test`, inspect the generated callback names, and verify both
default and apply-enabled application contexts.
