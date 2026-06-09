# Maluca Benchmarks

> Measurement honesty first. Every number here is reproducible from
> `scripts/` against the local Docker stack, and where a method has a known
> bias it is named.

## How to reproduce

```bash
docker compose up -d                 # redis + backend + proxy
# attack profiles
python3 scripts/traffic/normal.py     --duration 30 --rps 5
python3 scripts/traffic/burst.py      --duration 15 --workers 20
python3 scripts/traffic/scan.py       --duration 15
python3 scripts/traffic/credstuff.py  --duration 15
python3 scripts/traffic/lowslow.py    --duration 30 --clients 50
python3 scripts/traffic/distributed.py --duration 15 --ips 100
# latency
python3 scripts/bench/latency_bench.py --target http://localhost:8080 --rps 200 --duration 30
DURATION=30s RATE=2000 scripts/bench/run_wrk2.sh    # if wrk2 is installed
```

## Coordinated omission — read this first

The single most common way latency benchmarks lie: a closed-loop client
sends request N+1 only after N returns. When the server stalls for 200ms,
the ~40 requests that *should* have been sent during that stall are never
sent, so the stall under-samples itself and the p99 looks great. This is Gil
Tene's **coordinated omission**.

- `ab` (ApacheBench) has this bias and should not be trusted for tail
  latency.
- `wrk2` corrects it by holding a constant *offered* rate on a wall-clock
  schedule and measuring each request from its *intended* send time. Use it
  for real numbers.
- `scripts/bench/latency_bench.py` applies the same correction in stdlib
  Python (intended-send-time measurement), so the zero-dependency fallback
  is at least honest, if lower-throughput than wrk2.

Always lead with percentiles, never averages. An average hides exactly the
tail an attacker (or a bad GC pause) lives in.

## Added latency (measured)

`maluca_added_latency_seconds` is Maluca's own histogram of the time between
accepting a request and starting the upstream call — identity extraction,
the single Redis state round trip, scoring, and the decision. Measured on the
serving path, by the proxy, not inferred from the outside.

Local run, Apple Silicon, Redis via Homebrew on the same host, ~27.6k
requests including sustained burst load:

| percentile | added latency |
|---|---|
| p50 | ~1.2 ms |
| p95 | ~3.6 ms |
| p99 | ~5.0 ms |
| p99.9 | ~22 ms |

The p50 is dominated by one Redis round trip (the `collect_state` Lua
script); the tail is JVM GC pauses and Redis contention under burst. The
**p99 < 5ms** SLO holds in this setup; under the documented 10k-rps target it
needs Redis on the same host/AZ and G1 tuned — the number to defend in an
interview is "p99 added latency under 5ms with co-located Redis, measured by
our own histogram, coordinated-omission-corrected."

### Where the time goes (Jaeger sub-spans)

Every request carries `maluca.state`, `maluca.ratelimit`, and
`maluca.upstream` child spans. In practice `maluca.state` (Redis) is the
whole added-latency story; scoring is pure CPU and rounds to zero. This is
why the latency alert's runbook says "check the state span — it's Redis."

## Mitigation effectiveness (measured)

From live runs against the default policy set:

| Profile | Result | Reading |
|---|---|---|
| normal (5 rps, browser headers) | 100% allowed | no false positives on reference traffic |
| burst (20 workers, one IP) | 99.5% mitigated (mostly 403) | rapid escalation ALLOW→429→BLOCK with sticky hysteresis |
| credential stuffing (/login) | 5 reach backend, rest 403/429 | sliding-window-log cap of 5/60s on the sensitive route holds exactly |
| path scan (1 client) | ~67% mitigated | distinct-paths + 4xx-ratio signals trip the score |
| low-and-slow (50 IPs, 1 rps each) | evaded under NETWORK keying; caught under FINGERPRINT/COMPOSITE | the honest result: per-IP limits don't catch it — composite identity does |
| distributed (100 spoofed IPs) | per-IP limits pass it through | motivates global anomaly detection (Phase F) — documented, not hidden |

## Where Maluca loses (honesty section)

- **Distributed low-rate floods** spread across enough IPs stay under every
  per-identity threshold. Composite/fingerprint keying catches the
  same-tooling case; genuinely distributed human-like traffic needs the
  cross-session behavioral models in Phase F2, which aren't built.
- **The JVM tail.** A cold G1 or a large young-gen collection adds
  single-digit-ms spikes that a tuned C/Rust proxy wouldn't have. NGINX
  `limit_req` will beat Maluca's p99.9 on raw rate limiting — but it can't
  score, challenge, or run a hot-reloadable policy engine, which is the
  trade.
- **Single Redis** is the throughput ceiling and a shared failure domain.
  Phase 9 adds the circuit breaker and per-route fail-open/closed so a Redis
  stall degrades instead of cascading; it does not remove the ceiling.

## vs NGINX `limit_req` / Envoy local rate limit

Not yet run head-to-head (needs the comparison configs stood up). Expected
and defensible position: NGINX/Envoy win on raw added-latency p99.9 for
pure rate limiting (no scoring, native code, no GC); Maluca's value is
everything past a fixed counter — risk scoring, progressive mitigation,
proof-of-work challenges, composite identity, and config-hot-reload — in one
L7 hop. The benchmark to add: all three at 2000 rps constant via wrk2,
comparing the added-latency delta over direct-to-backend.
