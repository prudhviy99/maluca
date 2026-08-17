package com.maluca.contracts.incident;

/** Deterministic condition that opened an incident. */
public enum IncidentTrigger {
    MITIGATION_SPIKE,
    CHALLENGE_BLOCK_SURGE,
    TRAFFIC_VOLUME_SURGE,
    REDIS_DEGRADATION,
    MANUAL
}
