package com.maluca.mcp.tool;

import java.util.UUID;
import java.util.Locale;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;

import com.fasterxml.jackson.databind.JsonNode;
import com.maluca.contracts.policy.ApprovalRequest;
import com.maluca.mcp.client.TriageClient;
import com.maluca.mcp.validation.PolicyPatchValidator;
import com.maluca.mcp.validation.ToolInputException;

/** Opt-in capability kept out of the default agent provider. */
public class HumanApprovalMcpTools {

    private final TriageClient triageClient;
    private final PolicyPatchValidator validator;
    private final String approvalPrincipal;

    public HumanApprovalMcpTools(
            TriageClient triageClient,
            PolicyPatchValidator validator,
            String approvalPrincipal) {
        this.triageClient = triageClient;
        this.validator = validator;
        this.approvalPrincipal = approvalPrincipal;
    }

    @Tool(name = "approve_and_apply", description = "Human-only: atomically approve and apply the reviewed proposal using optimistic incident and policy versions.")
    @PreAuthorize("hasRole('OPERATOR')")
    public JsonNode approveAndApply(
            @ToolParam(description = "Incident whose reviewed proposal will be applied", required = true)
            UUID incidentId,
            @ToolParam(description = "Exact immutable proposal UUID reviewed by the operator", required = true)
            UUID proposalId,
            @ToolParam(description = "SHA-256 of the canonical stored proposal patch", required = true)
            String expectedProposalSha256,
            @ToolParam(description = "Expected current policy file SHA-256", required = true)
            String expectedPolicySha256,
            @ToolParam(description = "Expected current incident version", required = true)
            Long expectedIncidentVersion) {
        if (incidentId == null) {
            throw new ToolInputException("incidentId is required");
        }
        ApprovalRequest request = new ApprovalRequest(
                proposalId,
                expectedProposalSha256 == null ? null
                        : expectedProposalSha256.toLowerCase(Locale.ROOT),
                expectedPolicySha256 == null ? null : expectedPolicySha256.toLowerCase(Locale.ROOT),
                expectedIncidentVersion == null ? -1 : expectedIncidentVersion,
                approvalPrincipal);
        validator.validateApproval(request);
        return triageClient.approveAndApply(incidentId, request);
    }
}
