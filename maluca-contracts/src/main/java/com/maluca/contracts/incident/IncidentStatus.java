package com.maluca.contracts.incident;

/** Auditable lifecycle of an incident and its proposed remediation. */
public enum IncidentStatus {
    OPEN,
    TRIAGING,
    /** Automated triage exhausted its retry budget and now requires an operator. */
    TRIAGE_FAILED,
    TRIAGED,
    APPROVED,
    APPLIED,
    APPLY_INDETERMINATE,
    RESOLVED,
    DISMISSED,
    APPLY_FAILED
}
