# Maluca SLOs

**SLI** = the measurement. **SLO** = the internal target on that measurement.
**SLA** = a contractual promise to someone else, with consequences. Maluca
publishes SLOs; an SLA would be built on top of them with margin to spare.

## SLO 1 — Availability of the allow path

- **SLI:** fraction of requests that should be allowed (final action
  ALLOW/OBSERVE/SOFT_LIMIT) that complete with a non-5xx response.
  `1 - (rate(maluca_upstream_errors_total) / rate(maluca_decisions_total{action=~"ALLOW|OBSERVE|SOFT_LIMIT"}))`
- **SLO:** 99.9% over 30 days. Error budget ≈ 43 min/month.
- **Alert:** `MalucaUpstreamErrors`, `MalucaRedisErrors` (Redis failures
  degrade per route fail-mode; fail-closed routes consume this budget).

## SLO 2 — Added latency

- **SLI:** p99 of `maluca_added_latency_seconds` — time Maluca spends on
  identity + state + scoring + decision *before* the upstream call. Measured
  by us, on the serving path, not by a vendor benchmark.
- **SLO:** p99 < 5ms while Redis is co-located (same host/AZ).
- **Alert:** `MalucaAddedLatencyP99High` (page after 10 min over).
- **Debug path:** Jaeger trace → which sub-span grew (`maluca.state`,
  `maluca.ratelimit`, `maluca.upstream`); 9 times out of 10 it's Redis RTT.

## SLO 3 — Accuracy (false positives)

- **SLI:** in shadow/dry-run mode against reference traffic
  (scripts/traffic/normal.py), the fraction of known-good requests that
  would have received HARD_LIMIT or worse:
  `maluca_route_decisions_total{mode="DRY_RUN", action=~"HARD_LIMIT|CHALLENGE|BLOCK"}`.
- **SLO:** < 0.1% of reference traffic.
- **Alert:** `MalucaBlockRateAnomaly` approximates this in production (block
  ratio > 30% for 15 min is either an attack or a bad threshold change).
- **Process:** every threshold change ships in DRY_RUN first, watches this
  metric for a representative window, then flips to ENFORCE.

## The four golden signals → Maluca metrics

| Signal | Metric |
|---|---|
| Latency | `maluca_added_latency_seconds`, `maluca_upstream_latency_seconds` |
| Traffic | `maluca_decisions_total` (sum) |
| Errors | `maluca_upstream_errors_total`, `maluca_redis_errors_total` |
| Saturation | upstream pool pending acquires (reactor-netty metrics), Redis RTT growth in `maluca.state` span |

## Cardinality rules

Labels are bounded sets only: `action` (6 values), `route` (policy names,
~handful), `mode` (3), `event` (challenge funnel). Never label by client IP,
raw path, or user id — one careless label and the metrics backend eats the
cardinality product of all of them.
