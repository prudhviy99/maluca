package com.maluca.contracts.incident;

/** Supported incident classifications returned by the triage agent. */
public enum Classification {
    BURST_FLOOD,
    DISTRIBUTED_FLOOD,
    PATH_SCAN,
    CREDENTIAL_STUFFING,
    LOW_AND_SLOW,
    REDIS_DEGRADATION,
    FALSE_POSITIVE_WAVE,
    UNKNOWN
}
