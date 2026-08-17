# Maluca Runbook: Credential Stuffing

Incident class: `CREDENTIAL_STUFFING`. This is trusted operator guidance for
repeated authentication attempts, especially attempts distributed across
usernames or source identities. Never include submitted credentials or request
bodies in an incident report.

## Symptoms

- On the shipped exact `login` policy (`/login`), more than 5 requests in 60
  seconds per resolved key breaches the `SLIDING_WINDOW_LOG` limiter. The
  policy uses `FAIL_CLOSED` and stricter score bands: 20/35/50/60/80.
- `sensitive_60s` and `limit_exceeded` are expected leading contributions;
  `burst_10s`, `sustained_60s`, `fourxx_60s`, `ua_class_script_client`,
  `datacenter`, and `prior_escalation` can reinforce them.
- The action mix commonly escalates to `HARD_LIMIT`, `CHALLENGE`, and `BLOCK`
  on `/login`. Many failed or rejected attempts and changing account
  identifiers are typical, but account identifiers must come from an approved
  privacy-safe aggregate, not raw request-body storage.
- When Redis is unavailable, the login policy's `FAIL_CLOSED` behavior can
  also create 403 responses. That condition supports `REDIS_DEGRADATION`, not
  credential stuffing without traffic evidence.

## Confirm

1. Use `get_decisions` for policy `login` and the incident window. Confirm the
   resolved route is `/login`, inspect bounded client/action samples, and never
   request or persist passwords, tokens, authorization headers, or bodies.
2. Use `get_signal_breakdown` to verify `sensitive_60s` and
   `limit_exceeded`/rate signals dominate. Compare top-client concentration
   with a distributed set of low-rate keys.
3. Use `query_metrics` for login request rate, 401/403/429 outcomes, challenge
   results, Redis errors, and upstream authentication errors. Correlate with
   the identity provider's privacy-approved aggregate failure/lockout metrics.
4. Check `list_policies` to confirm the active exact `login` policy, its 5/60
   sliding-window-log limit, bands, mode, and `FAIL_CLOSED` setting. Review
   recent reloads.
5. Escalate immediately through the security response process if evidence
   indicates successful account takeover. Maluca containment does not revoke
   sessions, rotate credentials, or notify affected users.

## Remediate

Every policy mutation is reviewed and approved by a human. The agent may use
`propose_policy_patch` to stage an exact-login-policy change, but it cannot
approve or apply it.

1. If the shipped limiter and progressive actions are containing attempts,
   keep them active and coordinate account/session protection with the
   authentication owner.
2. For a confirmed campaign, consider a route-scoped proposal that lowers the
   `/login` limiter `limit`, lengthens its `window-seconds`, or lowers its
   challenge/block band minima. Lower values are stricter for limits and band
   minima. Preserve ordered bands and validate impact on password managers,
   shared networks, and retrying clients.
3. Prefer `CHALLENGE` for browser login when legitimate users can complete it.
   API/mobile clients may not support Maluca's challenge flow; use documented
   client-aware policies or `HARD_LIMIT` rather than silently breaking them.
4. Add a source CIDR to the `login` denylist only with strong attribution and
   a time limit. Do not allowlist broad shared networks on authentication
   routes to bypass stuffing controls.
5. Stage uncertain changes in `DRY_RUN`, evaluate normal login fixtures and
   would-have-acted rates, then seek explicit approval for `ENFORCE`.

## False-positive checks

- Check identity-provider outage/retry loops, expired application sessions,
  mobile release bugs, password-manager retries, SSO callback loops, QA load
  tests, and legitimate corporate NAT egress.
- Confirm whether Redis is degraded. `/login` deliberately fails closed when
  state is unavailable, which can resemble a block wave.
- Compare authentication success rate and unique-account aggregates without
  exposing account names. A volume spike alone is insufficient.
- Confirm trusted forwarding and identity keying are correct; one shared NAT
  can make many people look like one source, while untrusted XFF must not be
  used for attribution.
- A change immediately following a login policy reload with diverse known-good
  users affected may be `FALSE_POSITIVE_WAVE`.

## Rollback

1. Pause further policy approvals and coordinate with the authentication owner
   before relaxing containment during an active campaign.
2. Have an authorized operator restore the pre-change `login` policy backup or
   approve an inverse patch, then reload the proxy and verify with
   `list_policies`.
3. Confirm the 5/60 sliding-window-log setting, bands, mode, lists, and intended
   `FAIL_CLOSED` value are active. Do not change fail behavior accidentally as
   part of a limiter rollback.
4. Monitor legitimate login success, 403/429, challenge completion, Redis
   health, and attack indicators. If abuse returns, choose a narrower approved
   control and continue account-level incident response.

