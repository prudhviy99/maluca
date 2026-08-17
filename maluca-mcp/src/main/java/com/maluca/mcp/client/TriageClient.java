package com.maluca.mcp.client;

import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maluca.contracts.policy.ApprovalRequest;
import com.maluca.contracts.policy.PolicyProposalRequest;
import com.maluca.mcp.config.MalucaMcpProperties;

@Component
public class TriageClient {

    private final BoundedJsonClient http;
    private final BoundedJsonClient approvalHttp;
    private final MalucaMcpProperties.Limits limits;

    public TriageClient(
            @Qualifier("triageRestClient") RestClient restClient,
            @Qualifier("triageApprovalRestClient") RestClient approvalRestClient,
            ObjectMapper objectMapper,
            MalucaMcpProperties properties) {
        this.http = new BoundedJsonClient("maluca-triage", restClient, objectMapper,
                properties.triage().maxResponseBytes());
        this.approvalHttp = new BoundedJsonClient(
                "maluca-triage-approval", approvalRestClient, objectMapper,
                properties.triage().maxResponseBytes());
        this.limits = properties.limits();
    }

    public JsonNode getIncidents(String status, int limit) {
        int boundedLimit = Math.min(boundedResultLimit(limit), 100);
        JsonNode result = http.get(uri -> {
            uri.path("/api/v1/incidents").queryParam("limit", boundedLimit);
            if (status != null) {
                uri.queryParam("status", status);
            }
            return uri.build();
        });
        return JsonResultLimits.requireArray("get_incidents", result, boundedLimit);
    }

    public JsonNode getDecisions(
            String policy,
            String clientKey,
            String action,
            Instant from,
            Instant to,
            int limit) {
        int boundedLimit = boundedResultLimit(limit);
        JsonNode result = http.get(uri -> {
            uri.path("/api/v1/decisions").queryParam("limit", boundedLimit);
            if (policy != null) {
                uri.queryParam("policy", policy);
            }
            if (clientKey != null) {
                uri.queryParam("client_key", clientKey);
            }
            if (action != null) {
                uri.queryParam("action", action);
            }
            if (from != null) {
                uri.queryParam("from", from.toString());
            }
            if (to != null) {
                uri.queryParam("to", to.toString());
            }
            return uri.build();
        });
        return JsonResultLimits.requireArray("get_decisions", result, boundedLimit);
    }

    public JsonNode getSignalBreakdown(String policy, Instant from, Instant to) {
        JsonNode result = http.get(uri -> {
            uri.path("/api/v1/signals").queryParam("policy", policy);
            if (from != null) {
                uri.queryParam("from", from.toString());
            }
            if (to != null) {
                uri.queryParam("to", to.toString());
            }
            return uri.build();
        });
        return JsonResultLimits.requireObject("get_signal_breakdown", result);
    }

    public JsonNode searchRunbooks(String query, int limit) {
        int boundedLimit = Math.min(Math.max(1, limit), limits.maxRunbookLimit());
        JsonNode result = http.get(uri -> uri.path("/api/v1/runbooks/search")
                .queryParam("query", query)
                .queryParam("k", boundedLimit)
                .build());
        return JsonResultLimits.requireArray("search_runbooks", result, boundedLimit);
    }

    public JsonNode proposePolicyPatch(PolicyProposalRequest request) {
        return requireProposalReceipt("propose_policy_patch", () ->
                http.post("/api/v1/proposals", request, "propose_policy_patch"));
    }

    public JsonNode approveAndApply(UUID incidentId, ApprovalRequest request) {
        return requireProposalReceipt("approve_and_apply", () ->
                approvalHttp.post("/api/v1/incidents/" + incidentId + "/apply", request,
                        "approve_and_apply"));
    }

    private static JsonNode requireProposalReceipt(
            String operation, java.util.function.Supplier<JsonNode> request) {
        try {
            JsonNode result = JsonResultLimits.requireObject(operation, request.get());
            requireUuid(result, "id");
            requireUuid(result, "incidentId");
            requireDigest(result, "proposalSha256");
            requireDigest(result, "policySha256");
            if (!result.path("status").isTextual() || result.path("status").textValue().isBlank()) {
                throw new UpstreamServiceException(operation, 0,
                        operation + " returned a proposal receipt without status");
            }
            return result;
        } catch (IndeterminateUpstreamOperationException indeterminate) {
            throw indeterminate;
        } catch (RuntimeException invalidResponse) {
            throw new IndeterminateUpstreamOperationException(operation, invalidResponse);
        }
    }

    private static void requireUuid(JsonNode result, String field) {
        if (!result.path(field).isTextual()) {
            throw new UpstreamServiceException(field, 0,
                    "proposal receipt is missing " + field);
        }
        try {
            UUID.fromString(result.path(field).textValue());
        } catch (IllegalArgumentException invalid) {
            throw new UpstreamServiceException(field, 0,
                    "proposal receipt contains invalid " + field);
        }
    }

    private static void requireDigest(JsonNode result, String field) {
        if (!result.path(field).isTextual()
                || !result.path(field).textValue().matches("[0-9A-Fa-f]{64}")) {
            throw new UpstreamServiceException(field, 0,
                    "proposal receipt contains invalid " + field);
        }
    }

    private int boundedResultLimit(int requested) {
        return Math.min(Math.max(1, requested), limits.maxResultLimit());
    }
}
