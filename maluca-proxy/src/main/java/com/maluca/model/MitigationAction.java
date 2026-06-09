package com.maluca.model;

/**
 * Progressive mitigation pipeline, ordered from least to most severe.
 * Severity ordering matters: hysteresis only ever holds a client at the same
 * or a more severe action than the score alone would produce.
 */
public enum MitigationAction {

    /** Pass through to upstream untouched. */
    ALLOW,

    /** Pass through, but log at elevated detail and mark the client. */
    OBSERVE,

    /** Pass through after an artificial delay (traffic shaping). */
    SOFT_LIMIT,

    /** Reject with 429 Too Many Requests. */
    HARD_LIMIT,

    /** Serve a challenge (proof-of-work or JS-lite) instead of the upstream response. */
    CHALLENGE,

    /** Reject with 403 Forbidden. */
    BLOCK;

    public boolean passesThrough() {
        return this == ALLOW || this == OBSERVE || this == SOFT_LIMIT;
    }

    public boolean isAtLeastAsSevereAs(MitigationAction other) {
        return ordinal() >= other.ordinal();
    }
}
