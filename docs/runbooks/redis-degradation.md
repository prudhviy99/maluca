# Maluca Runbook: Redis Degradation

Incident class: `REDIS_DEGRADATION`. This is trusted operator guidance for a
slow, unavailable, or circuit-breaker-isolated Redis state backend. Redis is
on Maluca's decision path; pgvector/PostgreSQL used by triage is a separate
dependency.

## Symptoms

- `maluca_redis_errors_total` increases and the proxy health detail
  `components.redisBreaker.details.breakerState` moves to `OPEN`. The overall
  actuator status deliberately remains `UP`, with degradation reported as
  `PASSTHROUGH` and `degraded: true`.
- Decision reasons become `redis_down_fail_open` or
  `redis_down_fail_closed`, with empty contribution maps because stateful
  scoring and limiting could not run.
- Policies using `FAIL_OPEN` execute `ALLOW`; `FAIL_CLOSED` policies execute
  `BLOCK`. The shipped exact `/login` policy is `FAIL_CLOSED`; the other
  shipped policies default to `FAIL_OPEN`.
- `/login` 403s can surge while other routes pass unchecked. This mixed action
  pattern, combined with empty contributions and Redis errors, distinguishes
  degradation from a normal traffic attack.

## Confirm

1. Read `GET /actuator/health` on every proxy instance and inspect
   `redisBreaker`. Use `query_metrics` for the rate of
   `maluca_redis_errors_total` and proxy latency; a slow call beyond the
   configured Redis timeout counts as a failure.
2. Call `get_decisions` for the incident window and confirm the
   `redis_down_fail_open`/`redis_down_fail_closed` reason and empty
   contributions. Do not classify an attack based only on degraded actions.
3. From the Redis network boundary, run `redis-cli PING`, `INFO memory`,
   `INFO clients`, and `SLOWLOG GET` using the deployment's authenticated/TLS
   connection method. Check saturation, eviction, failover, DNS, and packet
   loss.
4. Use `list_policies` to enumerate actual fail modes. Confirm whether any
   recent reload changed `FAIL_OPEN`/`FAIL_CLOSED` before proposing a policy
   action.
5. Distinguish this from triage PostgreSQL failure: loss of incident storage
   or pgvector retrieval does not open the proxy's Redis breaker.

## Remediate

Restore Redis service/capacity/connectivity first; no AI-generated proxy policy
change repairs Redis. Any temporary fail-mode change is a security/business
decision requiring explicit human approval.

1. Stop traffic generators and nonessential Redis consumers, then recover the
   configured Redis primary/replica or managed service according to its own
   failover procedure. Do not flush Maluca keys as a first response.
2. Verify `PING` latency and error rate are stable. The circuit breaker waits
   the configured open-state interval (10 seconds by default), enters
   half-open, permits probe calls, and closes automatically after success.
3. If a prolonged outage makes a `FAIL_CLOSED` route's availability impact
   unacceptable, an authorized operator may propose changing that one
   policy to `FAIL_OPEN`. Document the increased abuse risk, owner, expiry,
   and rollback. The triage agent must not approve it.
4. If a sensitive `FAIL_OPEN` route creates unacceptable exposure, a human may
   choose `FAIL_CLOSED` only after assessing outage amplification and ensuring
   an alternate safe user flow.

## False-positive checks

- A single timeout or counter increment before the breaker opens is not by
  itself sustained degradation. Correlate across instances and windows.
- Confirm actuator details come from the affected proxy, not the triage or MCP
  service, which have their own health endpoints.
- Check a proxy restart or deployment cold start, Redis maintenance window,
  DNS rotation, and intentional chaos tests such as
  `scripts/chaos/kill_redis.sh`.
- An attack can increase Redis work and trigger degradation; preserve both
  diagnoses when evidence supports them, but restore state service before
  trusting traffic contributions.
- Empty contributions plus fail-open/closed reasons are expected degraded
  decisions, not evidence that the scoring model malfunctioned.

## Rollback

1. After Redis health and the breaker remain stable, revert every temporary
   fail-mode change to its pre-incident value through the human approval path.
2. Reload the proxy and use `list_policies` to verify each active fail mode;
   confirm `/login` is back to its intended `FAIL_CLOSED` posture unless an
   approved permanent decision says otherwise.
3. Confirm health shows breaker `CLOSED`, degradation `FULL`, and `degraded:
   false`; verify the Redis error rate is no longer increasing and scored
   decisions again contain contributions.
4. If the policy reload or recovery worsens errors, restore the policy backup,
   keep the last-known-good policy active, and return to Redis diagnosis. Do
   not flush keys or clear incident history as a rollback shortcut.

