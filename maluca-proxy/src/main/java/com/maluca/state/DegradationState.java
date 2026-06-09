package com.maluca.state;

/**
 * Graceful-degradation tiers. As Redis health drops, Maluca sheds the parts
 * of the pipeline that need shared state rather than failing requests
 * outright.
 */
public enum DegradationState {
    /** Everything: state, scoring, limiting, challenges. */
    FULL,
    /** Redis flaky: skip stateful scoring, keep best-effort limiting. */
    RATE_LIMIT_ONLY,
    /** Redis down: pure pass-through (fail-open routes) — no shared state at all. */
    PASSTHROUGH
}
