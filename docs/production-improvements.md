# Production Improvements

Everything in this file is something we deliberately skipped for the interview build
due to time constraints. Review this before your interview — these are exactly the
"what would you do in production?" and "what are the limitations?" questions Cloudflare
will ask. For each item: know what it is, why it matters, and what the tradeoff is.

---

## 1. WebFlux (reactive) instead of Spring MVC (blocking)

**What we built:** Blocking Spring MVC. Each request parks a thread while waiting
for Redis and the upstream backend.

**What production needs:** Spring WebFlux with an event-loop model (Netty).
A proxy is 100% I/O-bound — the thread does nothing while waiting on Redis and
upstream. With blocking MVC, 1000 concurrent requests = 1000 threads.
With WebFlux, the same 1000 requests run on ~8 threads, with each thread
switching between requests whenever one is waiting on I/O.

**Tradeoff:** WebFlux is harder to write and reason about (everything returns
`Mono<T>` instead of `T`), so it was the wrong choice for a 1-week build.

**What to say:** "I'd use WebFlux in production because a proxy is entirely I/O-bound.
The event-loop model handles much higher concurrency without scaling thread count linearly.
I used Spring MVC here to keep the implementation straightforward."

---

## 2. Sliding window counters (Redis sorted sets) instead of fixed windows (INCR + EXPIRE)

**What we built:** Fixed window — INCR a key, EXPIRE it after 10 seconds.

**The problem:** Boundary spike. A client can send 30 requests at second 9 of window 1
and 30 requests at second 1 of window 2 — that's 60 requests in 2 seconds, but both
windows see 30 and neither triggers the limit.

**What production needs:** Sliding window using a Redis sorted set.
`ZADD client:ip:requests <timestamp> <request_id>`, then `ZCOUNT` the entries
in the last 10 seconds. Accurate, no boundary spike.

**Tradeoff:** ZADD is O(log N) vs INCR which is O(1). For very high traffic,
sorted sets are more expensive. Fixed window is a reasonable first cut.

**What to say:** "The INCR approach has a boundary spike problem where a burst
spanning two windows goes undetected. The production fix is a Redis sorted set
sliding window — store timestamps as scores, ZCOUNT the last N seconds. It's
slightly more expensive (O log N) but removes the blind spot."

---

## 3. Path scanning signal (distinct path counting)

**What we skipped:** Tracking how many distinct URLs a client hits in 30 seconds.
A client hitting 20 different paths (/.env, /admin, /wp-login, etc.) in 30s is
clearly a scanner even if their raw request rate looks normal.

**What production needs:** `SADD client:ip:paths:30s <path>` then `SCARD` for
distinct count. Or `PFADD`/`PFCOUNT` with HyperLogLog for approximate counting
at lower memory cost.

**Why we skipped it:** Adds another Redis key per request and another signal to
implement. The 2 signals we have are enough to demo the scoring concept.

**What to say:** "I tracked request rate and sensitive endpoint hits for the prototype.
A real system would also track distinct path count — a scanner probing 50 URLs at
low frequency looks clean on rate signals but lights up on path variance."

---

## 4. OBSERVE and SOFT_RATE_LIMIT actions

**What we built:** 3 actions: ALLOW, RATE_LIMIT (429), BLOCK (403).

**What production needs:**
- `OBSERVE` — allow the request but flag the client for enriched logging and
  closer monitoring. Lets you watch a suspicious client without risking false positives
  before you're confident enough to rate limit.
- `SOFT_RATE_LIMIT` — add an artificial delay (e.g. 500ms) before forwarding.
  Bots that send thousands of requests care about latency; real users on one browser
  tab don't notice 500ms. This degrades bot throughput without breaking legit users.

**What to say:** "The graduated OBSERVE and SOFT_RATE_LIMIT stages reduce false
positive risk. Instead of jumping from allow to 429, you can watch suspicious clients
and slow them down before fully blocking them."

---

## 5. Redis failure handling

**What we built:** If Redis is down, requests will throw exceptions and likely return 500.

**What production needs:** Fail open — if Redis is unreachable, allow the request
and log an alert. It's better to let some attack traffic through than to take down
your entire service because your rate limiter's state store is unavailable.

Options:
- Try/catch around Redis calls, fall through to ALLOW on exception
- Local in-memory fallback cache for short Redis outages (with a much lower limit)
- Redis Sentinel or Redis Cluster for HA so downtime is rare

**What to say:** "The right failure mode for a rate limiter is fail open — allow
traffic if you can't check state. Fail closed (blocking everything) is a self-inflicted
outage. In production I'd wrap Redis calls with a fallback and alert on degraded mode."

---

## 6. Shared IP problem (corporate NAT, mobile carriers)

**What we built:** Client key = IP address. One IP = one client.

**The problem:** Many real users can share the same IP (corporate office behind NAT,
university network, mobile carrier CG-NAT). Blocking that IP blocks all of them.

**What production needs:**
- Use X-Forwarded-For when behind a trusted load balancer (we have the config for this)
- Supplement IP with other signals: user-agent, cookie/session token, TLS fingerprint (JA4)
- Rate limit at a higher threshold for IPs known to be shared

**What to say:** "IP-only identity breaks down behind NAT. The full version would
use a composite key — IP + session token + TLS fingerprint. JA4 fingerprinting
(successor to JA3) gives you a client identity that survives IP rotation."

---

## 7. Per-route policy overrides

**What we built:** One global threshold for all paths.

**What production needs:** `/login` should have a stricter threshold than `/api/products`.
10 login attempts per minute might be suspicious; 10 product page views per minute is normal.

**How:** Extend `MalucaConfig` to support a list of route-specific policy overrides.
When scoring, look up the policy for the current path first, fall back to default.

**What to say:** "In production you'd have per-route policies. Sensitive endpoints
like /login get much tighter thresholds than commodity API paths. The config structure
is already set up for this — the default policy is the fallback."

---

## 8. Admin API

**What we skipped:** No way to manually block/unblock a client or tune thresholds
without restarting the service.

**What production needs:**
- `POST /admin/block/{clientKey}` — manually add a Redis block entry
- `DELETE /admin/block/{clientKey}` — remove a block
- `GET /admin/state/{clientKey}` — inspect current counters for a client
- Protected by an API key or internal-only network rule

---

## 9. Metrics and observability depth

**What we built:** Basic Micrometer counters by action.

**What production needs:**
- Top N suspicious clients by score (who's currently elevated?)
- False positive review — which blocked clients had normal behavior before?
- p50/p95/p99 latency added by Maluca specifically (not upstream)
- Decision breakdown: what signals triggered for each decision?

---

## 10. Reputation decay

**What we skipped:** Once a client's counters drop below threshold after behaving well,
their score naturally goes to 0. But a client that was blocked yesterday and is now
technically clean is still suspicious.

**What production needs:** A separate reputation score that decays slowly.
A client that triggered BLOCK last hour should still get elevated scrutiny even
if their current rate is low. This prevents hit-and-run attacks where a bot
bursts, backs off, and bursts again.
