# Maluca Runbook

One-page "what to do when…" for operating Maluca. Pairs with `docs/slos.md`
(targets) and the Grafana dashboard (uid `maluca-main`).

## Quick reference

| Symptom | Dashboard panel | Likely cause | Action |
|---|---|---|---|
| p99 added latency alert | "Added latency" | Redis RTT | §1 |
| `MalucaRedisErrors` firing | "Errors (redis/upstream)" | Redis down/slow | §2 |
| Block rate spiking | "Decision breakdown" | attack OR bad threshold | §3 |
| Users report being blocked | decision logs | false positive | §4 |
| Challenge solve rate ~0 | "Challenge funnel" | broken challenge page | §5 |

## 1. p99 added latency over 5ms

1. Open the request in Jaeger; look at the `maluca.state` / `maluca.ratelimit`
   / `maluca.upstream` sub-spans. Whichever grew is the culprit.
2. Almost always `maluca.state` (Redis). Check Redis: `redis-cli --latency`,
   memory (`INFO memory`), and whether it's swapping.
3. If Redis is healthy, suspect JVM GC: check the proxy's GC logs; a large
   young-gen or a full GC shows as a latency spike. Bump heap / tune G1.
4. Mitigation while investigating: the breaker will trip if Redis is the
   cause and latency exceeds `resilience.redis-timeout-ms` — degradation is
   automatic and safe.

## 2. Redis is down / unreachable

- **Expected behavior (already automatic):** the breaker opens, the decision
  path short-circuits to each route's fail-mode. Fail-open routes keep
  serving (no scoring); fail-closed routes (e.g. `/login`) return 403.
  `/actuator/health` shows `degradation: PASSTHROUGH`, **status stays UP**
  (so liveness probes don't restart the instance).
- **Verify:** `curl /actuator/health | jq .components.redisBreaker`.
- **Fix Redis**, then do nothing — after `resilience.open-state-seconds` the
  breaker half-opens, samples a few calls, and closes on success. Full
  scoring resumes on its own.
- **If fail-closed is hurting more than helping** during a long outage, flip
  the affected policy's `fail-mode` to `FAIL_OPEN` and hot-reload
  (`POST /_maluca/admin/policies/reload`). No restart.

## 3. Block/limit rate suddenly high

1. Is it an attack? Check "Top routes by non-allow rate" and the decision
   logs for a dominant client key / fingerprint / ASN. If it's a real
   attack and legit traffic is unaffected — **let it ride**, that's the
   system working.
2. Did a threshold just change? Check recent policy reloads
   (`policies_loaded` log lines) and the "Dry-run would-have-acted" panel.
   If a tightening over-blocks, revert the policy file and reload.
3. Emergency global relax: set the offending policy `mode: OBSERVE` and
   reload — pipeline still scores and logs, but executes pass-through.

## 4. A specific user/client is wrongly blocked (false positive)

1. Find them in the decision log by IP/fingerprint; the log line carries the
   exact `signals` contributions that produced the score — read off which
   signal over-fired.
2. Short term: add their IP/CIDR to the route policy `allowlist`, reload.
3. Long term: that signal's weight or threshold is too aggressive. Lower it
   in a DRY_RUN policy first, watch the false-positive panel, then enforce.

## 5. Challenges issued but never solved

- Check "Challenge funnel": high `issued`, ~0 `solved` means either (a) only
  bots are being challenged (fine) or (b) the challenge page is broken for
  real browsers.
- The PoW page needs a **secure context** (https or localhost) for
  SubtleCrypto. If Maluca is served over plain http on a non-localhost host,
  `crypto.subtle` is unavailable and no one can solve. Serve via TLS.
- Verify the `maluca_pass` cookie is being set/accepted: solve manually in a
  browser and watch the network tab for the `Set-Cookie` on
  `/_maluca/challenge/verify`.

## 6. Secrets rotation

- `MALUCA_CHALLENGE_SECRET` rotation invalidates all outstanding challenge
  tokens and pass cookies — users mid-challenge get re-challenged once.
  Acceptable; rotate during low traffic. Roll the env var and restart
  instances one at a time.
- `MALUCA_ADMIN_TOKEN`: rotate the env var and restart; the admin API is the
  only consumer.

## 7. Deploying / scaling

- **Single instance:** `docker compose up`.
- **Multi-instance:** `docker compose -f docker-compose.yml -f
  docker-compose.multi.yml up redis demo-backend maluca-proxy-1
  maluca-proxy-2 lb`. Both instances share Redis, so limits/decisions are
  consistent across them — confirm by hammering one path through the LB and
  watching the count converge in Redis.
- **Zero-downtime:** instances are stateless (all shared state is in Redis),
  so rolling one at a time behind the LB is safe. Drain by removing from the
  upstream, wait for in-flight to finish (graceful shutdown is on by
  default), replace, re-add.
