package com.maluca.mcp.client;

/**
 * A state-changing request may have reached its upstream before the response was
 * lost. Operators must reconcile audited state before retrying.
 */
public class IndeterminateUpstreamOperationException extends UpstreamServiceException {

    public IndeterminateUpstreamOperationException(String operation, Throwable cause) {
        super("maluca-triage", operation
                + " outcome is indeterminate; inspect the incident/proposal audit state before retrying",
                cause);
    }
}
