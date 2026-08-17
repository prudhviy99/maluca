# Maluca Runbook: False-Positive Wave

Incident class: `FALSE_POSITIVE_WAVE`. This is trusted operator guidance for a
broad rise in mitigations against legitimate traffic, often following a
policy, identity, forwarding, application, or traffic-shape change.

## Symptoms

- `HARD_LIMIT`, `CHALLENGE`, or `BLOCK` rises across diverse known-good client
  keys and normal paths, with user complaints, conversion/login failures, or
  challenge abandonment.
- The increase aligns with a policy reload, application/client release,
  trusted-proxy change, traffic migration, or a normal demand event.
- Contributions may be weakly distributed across `burst_10s`,
  `sustained_60s`, `header_anomaly`, `ua_header_mismatch`, `limit_exceeded`,
  and `prior_escalation` rather than one coherent malicious pattern.
- `computedAction` can be severe while `executedAction` remains pass-through
  for `DRY_RUN`/`OBSERVE`. In `ENFORCE`, the full action is executed. Redis
  fail-closed decisions with reason `redis_down_fail_closed` belong under
  `REDIS_DEGRADATION` unless a separate bad-policy wave also exists.

## Confirm

1. Use `get_incidents` and bounded `get_decisions` samples to measure affected
   policies, actions, client diversity, paths, tiers, and the earliest change
   time. Compare the last known healthy window.
2. Use `get_signal_breakdown` to identify which contributions shifted. Check
   both computed and executed action distributions and the `dryRun` flag.
3. Use `list_policies` and policy reload/audit history to compare active values
   with the pre-event version. Maluca resolves the most-specific route, so
   verify which policy actually matched rather than assuming `default`.
4. Use `query_metrics` for request rate, 403/429, challenge issue/solve,
   upstream errors/latency, and Redis errors. Correlate with privacy-safe
   application success indicators and support reports.
5. Sample a known-good synthetic/browser cohort and a known-bad fixture. A
   false-positive diagnosis requires evidence of legitimate impact and should
   not erase evidence of a simultaneous attack.

## Remediate

Every mutation requires an authorized human. The agent may call
`propose_policy_patch` to stage a scoped inverse or relaxation, but it cannot
approve/apply.

1. For urgent customer impact, propose changing only the offending policy to
   `OBSERVE`; this continues scoring/logging while capping enforcement at
   pass-through. Use `DRY_RUN` for subsequent tuning comparisons. Do not relax
   unrelated routes globally.
2. Reverse the most recent causally linked patch when a known-good prior
   version exists. Otherwise, propose raising band minima or increasing the
   algorithm-appropriate rate limit/burst. Higher minima/limits are more
   permissive; keep bands monotonic and within 0–100.
3. Correct identity or trusted-proxy configuration if the wrong clients were
   grouped. Treat changes to global infrastructure settings as restart-bound
   deployment changes, not hot-reloadable policy fields.
4. Use an `allowlist` only as a short, narrow exception for a verified CIDR.
   It bypasses the scoring pipeline, so broad or permanent allowlists can hide
   abuse and are not a substitute for fixing thresholds.
5. Approve after reviewing route scope, customer impact, attack exposure,
   expiry, and rollback. Validate normal and attack traffic before restoring
   `ENFORCE`.

## False-positive checks

- Challenge the false-positive hypothesis: look for a true burst, distributed
  flood, path scan, credential campaign, or low-and-slow abuse overlapping the
  same window.
- Verify customer reports map to Maluca responses rather than upstream 403/429
  or authentication failures. Use trace/reason evidence.
- Check Redis health; the `/login` policy intentionally fails closed during a
  Redis outage.
- Confirm the affected cohort is known-good through application ownership or
  approved synthetic identities. Browser-like headers, a residential IP, or a
  low score alone do not prove legitimacy.
- Check challenge transport requirements. A browser unable to use the
  challenge flow can look falsely blocked even when classification was
  correct; fix challenge delivery where appropriate.

## Rollback

1. Record the active policy hash/version and incident evidence, then freeze
   additional approvals until one rollback owner is designated.
2. Have the authorized operator restore the last-known-good policy backup or
   approve an exact inverse patch. Reload through the proxy admin endpoint and
   require a successful response.
3. Use `list_policies` to verify the prior mode, bands, limiter, keying,
   allowlist/denylist, fail mode, and route order are active. If verification
   fails, keep or restore the last-known-good file and mark apply as failed.
4. Confirm legitimate success and challenge completion recover without an
   unacceptable rise in confirmed abuse. Keep the reverted policy in
   `DRY_RUN` for retuning and require a new approval before enforcement.

