package com.maluca.contracts.incident;

/** Operator acknowledgement used to close one terminal triage failure by CAS. */
public record IncidentDismissRequest(
        long expectedIncidentVersion,
        String reason) {
}
