# Rate-Limiting Algorithms in Maluca

Maluca implements five algorithms behind one interface
(`RateLimiter.check(key, config) → Mono<LimitDecision>`). Each is a single
Redis Lua script in `maluca-proxy/src/main/resources/lua/`, selected per
policy. All scripts read the clock with `redis.call("TIME")` — proxy
instances never trust their own clocks, so a fleet of Maluca nodes sharing
one Redis behaves as a single limiter with no skew.

**Why Lua?** Redis executes a script as one atomic unit (the server is
single-threaded for command execution; a script occupies that thread from
start to finish). Read-modify-write sequences like "check counter, then
increment" therefore can't interleave between two proxy instances — the
whole reason the naive `GET`+`INCR`+`EXPIRE` approach is broken is solved in
one move. The concurrency tests in `RateLimiterRedisTest` fire 200 parallel
checks and assert exactly N admissions.

---

## 1. Fixed window (`fixed_window.lua`)

**Mechanism.** Counter per `(client, ⌊now/window⌋)` bucket; `INCR`, reject above
the limit.

**Why it exists.** Cheapest possible: one integer per client per window, one
`INCR`. Trivial to reason about and to explain to API consumers ("100
requests per minute, resets on the minute").

**Failure mode.** Boundary burst: a client can send the full limit in the
last second of one window and the full limit in the first second of the next
— 2x the intended rate across a 2-second span.

**Memory.** O(1) per client (~50 bytes of key + integer).

**Right choice when.** MVPs, coarse abuse caps, `/health`-grade endpoints
where exactness is irrelevant and cost matters.

## 2. Sliding window counter (`sliding_window_counter.lua`)

**Mechanism.** Keep current and previous fixed-window counters. Estimated
rate = `current + previous × (overlap fraction of previous window)`.

**Why it exists.** Kills the boundary burst of fixed window for the same
O(1) memory, at the cost of an approximation that assumes uniform arrival in
the previous window. Cloudflare published that this shape misjudged only
~0.003% of requests across 400M requests sampled.

**Failure mode.** The uniformity assumption: a previous window whose traffic
was all in its first second over-counts; all in its last second
under-counts. Bounded error, never worse than fixed window.

**Memory.** O(1) per client (two integers).

**Right choice when.** The general-purpose default. Maluca's baseline cap
and most route policies should use this.

## 3. Sliding window log (`sliding_window_log.lua`)

**Mechanism.** ZSET of admitted-request timestamps (microseconds).
`ZREMRANGEBYSCORE` evicts entries older than the window, `ZCARD` is the
exact in-window count.

**Why it exists.** It is *exact*. No boundary artifacts, no approximation.
Also gives precise `Retry-After` (the oldest entry's expiry).

**Failure mode.** Memory. One ZSET entry (~80–100 bytes with overhead) per
admitted request in the window. A client allowed 1M requests/window costs
~100 MB *per client*. Note it's bounded by the limit, not the attack rate —
rejected requests are not logged — but a high-limit policy on this
algorithm is a self-inflicted memory bomb.

**Memory.** O(limit) per client.

**Right choice when.** Low-limit, high-value endpoints: `/login` (5/min),
password reset, OTP issuance — places where letting request #6 through is a
security event, not a rounding error.

## 4. Token bucket (`token_bucket.lua`)

**Mechanism.** Bucket holds up to `burst` tokens, refilled continuously at
`rate`/s (computed lazily from elapsed time — no timers). A request takes a
token or is rejected.

**Why it exists.** Decouples *average rate* from *burst tolerance*. A
browser loading a page legitimately fires 30 requests in a second, then
nothing for a minute; token bucket admits that burst while still enforcing
the long-run average. This is the algorithm of AWS API throttling and
Stripe's limiter.

**Failure mode.** A full-capacity burst is admitted instantly — if the
upstream can't absorb `burst` simultaneous requests, the bucket protected
the average but not the instantaneous load. Pick `burst` with the upstream's
concurrency in mind.

**Memory.** O(1) per client (two floats).

**Right choice when.** Public API quotas, anything consumed by real
applications with bursty-but-honest patterns. The Stripe `/charges` answer:
token bucket keyed by API key, modest burst, strict average.

## 5. Leaky bucket — policing variant (`leaky_bucket.lua`)

**Mechanism.** A virtual queue drains at a constant `rate`/s; each request
adds 1 if it fits within `capacity`, else immediate rejection. (The
*shaping* variant would queue and delay instead — in Maluca, shaping is the
mitigation layer's job: `SOFT_LIMIT` delays via `Mono.delay`.)

**Why it exists.** The *output* toward the upstream is perfectly smooth —
never more than `rate` per second regardless of input shape. Token bucket
bounds the average; leaky bucket bounds the instantaneous rate.

**Failure mode.** Hostile to legitimate burstiness: the page-load burst that
token bucket was designed to admit gets clipped at `capacity`. Latency-fair
but UX-poor for browser traffic.

**Memory.** O(1) per client (two floats).

**Right choice when.** The protected resource hates bursts: DB write paths,
third-party APIs with strict pacing contracts, downstream queues with fixed
consumers.

---

## Comparison at a glance

| | Memory/client | Exact? | Allows bursts? | Boundary artifact | Typical use |
|---|---|---|---|---|---|
| Fixed window | O(1) | no | within window | 2x at edges | crude caps |
| Sliding counter | O(1) | ~ (bounded err) | within window | none | **default** |
| Sliding log | O(limit) | **yes** | within window | none | /login, OTP |
| Token bucket | O(1) | yes (avg) | **yes, by design** | none | API quotas |
| Leaky bucket | O(1) | yes (instant.) | up to capacity | none | smooth output |

## Interview drill answers

- **Fixed window vs token bucket graph:** fixed window's admitted-requests
  curve is a staircase with discontinuities at window boundaries (and the
  2x burst straddling them); token bucket is a sloped line (rate) with an
  initial vertical jump (burst capacity).
- **Why is a Lua script atomic even though Redis is a network service?**
  Atomicity is a property of the *server's execution*, not the network:
  Redis runs commands on one thread, and an executing script is one
  uninterruptible unit in that command stream. Concurrent clients just queue.
- **Clock skew:** all scripts call `redis.call("TIME")`. Two proxy instances
  with clocks 3 seconds apart still agree on every window boundary because
  neither ever consults its own clock for limiting.
- **Fail-open vs fail-closed:** if Redis is down, none of these can answer.
  Whether to admit (availability) or reject (safety) is a *per-route policy*
  — fail open on product pages, fail closed on `/login`. See Phase 9
  resilience.
