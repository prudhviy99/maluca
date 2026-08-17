package com.maluca.mcp.tool;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.fasterxml.jackson.databind.JsonNode;
import com.maluca.contracts.policy.PolicyProposalRequest;
import com.maluca.mcp.client.MalucaProxyClient;
import com.maluca.mcp.client.PrometheusClient;
import com.maluca.mcp.client.TriageClient;
import com.maluca.mcp.validation.PolicyPatchValidator;
import com.maluca.mcp.validation.ToolInputException;
import com.maluca.mcp.validation.ToolInputValidator;

/** Tools safe for the incident-triage agent's default MCP provider. */
public class AgentMcpTools {

    private final TriageClient triageClient;
    private final MalucaProxyClient proxyClient;
    private final PrometheusClient prometheusClient;
    private final ToolInputValidator input;
    private final PolicyPatchValidator policyPatchValidator;

    public AgentMcpTools(
            TriageClient triageClient,
            MalucaProxyClient proxyClient,
            PrometheusClient prometheusClient,
            ToolInputValidator input,
            PolicyPatchValidator policyPatchValidator) {
        this.triageClient = triageClient;
        this.proxyClient = proxyClient;
        this.prometheusClient = prometheusClient;
        this.input = input;
        this.policyPatchValidator = policyPatchValidator;
    }

    @Tool(name = "get_incidents", description = "List a bounded set of Maluca incidents, optionally filtered by lifecycle status.")
    public JsonNode getIncidents(
            @ToolParam(description = "Optional incident status such as OPEN or TRIAGED", required = false)
            String status,
            @ToolParam(description = "Maximum incidents to return", required = false)
            Integer limit) {
        return triageClient.getIncidents(input.optionalStatus(status), input.incidentLimit(limit));
    }

    @Tool(name = "get_decisions", description = "Get bounded Maluca mitigation decisions using optional policy, client, action, and UTC time filters.")
    public JsonNode getDecisions(
            @ToolParam(description = "Optional policy name", required = false) String policy,
            @ToolParam(description = "Optional privacy-safe client key", required = false) String clientKey,
            @ToolParam(description = "Optional computed action", required = false) String action,
            @ToolParam(description = "Optional RFC 3339 UTC range start", required = false) String from,
            @ToolParam(description = "Optional RFC 3339 UTC range end", required = false) String to,
            @ToolParam(description = "Maximum decisions to return", required = false) Integer limit) {
        String boundedPolicy = input.optionalText("policy", policy, 128);
        String boundedClient = input.optionalText("clientKey", clientKey, 256);
        Instant boundedFrom = input.optionalInstant("from", from);
        Instant boundedTo = input.optionalInstant("to", to);
        input.validateWindow(boundedFrom, boundedTo);
        return triageClient.getDecisions(boundedPolicy, boundedClient, input.optionalAction(action),
                boundedFrom, boundedTo, input.resultLimit(limit));
    }

    @Tool(name = "get_signal_breakdown", description = "Get aggregate risk-signal contributions for one policy and an optional bounded UTC window.")
    public JsonNode getSignalBreakdown(
            @ToolParam(description = "Policy name", required = true) String policy,
            @ToolParam(description = "Optional RFC 3339 UTC range start", required = false) String from,
            @ToolParam(description = "Optional RFC 3339 UTC range end", required = false) String to) {
        String boundedPolicy = input.requiredText("policy", policy, 128);
        Instant boundedFrom = input.optionalInstant("from", from);
        Instant boundedTo = input.optionalInstant("to", to);
        input.validateWindow(boundedFrom, boundedTo);
        return triageClient.getSignalBreakdown(boundedPolicy, boundedFrom, boundedTo);
    }

    @Tool(name = "query_metrics", description = "Run restricted read-only PromQL over a bounded UTC range. Only approved Maluca/runtime metric namespaces are accepted.")
    public JsonNode queryMetrics(
            @ToolParam(description = "Restricted PromQL expression", required = true) String query,
            @ToolParam(description = "RFC 3339 UTC range start", required = true) String start,
            @ToolParam(description = "RFC 3339 UTC range end", required = true) String end,
            @ToolParam(description = "Whole-number query step in seconds", required = true) Long stepSeconds) {
        String boundedQuery = input.requiredText("query", query, input.maxQueryCharacters());
        if (stepSeconds == null || stepSeconds < 1 || stepSeconds > Integer.MAX_VALUE) {
            throw new ToolInputException("stepSeconds is outside the safe range");
        }
        return prometheusClient.queryRange(boundedQuery,
                input.requiredInstant("start", start), input.requiredInstant("end", end),
                Duration.ofSeconds(stepSeconds));
    }

    @Tool(name = "list_policies", description = "List the active compiled policies from the Maluca proxy admin API.")
    public JsonNode listPolicies() {
        return proxyClient.listPolicies();
    }

    @Tool(name = "search_runbooks", description = "Search trusted runbook chunks and return bounded source-cited retrieval context.")
    public JsonNode searchRunbooks(
            @ToolParam(description = "Natural-language incident or remediation query", required = true)
            String query,
            @ToolParam(description = "Maximum runbook chunks to return", required = false)
            Integer limit) {
        return triageClient.searchRunbooks(
                input.requiredText("query", query, input.maxQueryCharacters()),
                input.runbookLimit(limit));
    }

    @Tool(name = "propose_policy_patch", description = "Persist a typed, route-scoped policy proposal for human review. This never approves or applies a policy.")
    public JsonNode proposePolicyPatch(
            @ToolParam(description = "Incident receiving this proposal", required = true) UUID incidentId,
            @ToolParam(description = "Typed policy delta and rationale", required = true) PolicyPatchInput patch) {
        if (incidentId == null) {
            throw new ToolInputException("incidentId is required");
        }
        if (patch == null) {
            throw new ToolInputException("patch is required");
        }
        var contract = patch.toContract();
        policyPatchValidator.validate(contract);
        return triageClient.proposePolicyPatch(new PolicyProposalRequest(incidentId, contract));
    }
}
