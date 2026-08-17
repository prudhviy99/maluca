package com.maluca.mcp.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.maluca.mcp.client.MalucaProxyClient;
import com.maluca.mcp.client.PrometheusClient;
import com.maluca.mcp.client.TriageClient;
import com.maluca.mcp.tool.AgentMcpTools;
import com.maluca.mcp.tool.HumanApprovalMcpTools;
import com.maluca.mcp.validation.PolicyPatchValidator;
import com.maluca.mcp.validation.ToolInputValidator;

@Configuration(proxyBeanMethods = false)
public class ToolConfiguration {

    @Bean
    AgentMcpTools agentMcpTools(
            TriageClient triageClient,
            MalucaProxyClient proxyClient,
            PrometheusClient prometheusClient,
            ToolInputValidator inputValidator,
            PolicyPatchValidator policyPatchValidator) {
        return new AgentMcpTools(
                triageClient, proxyClient, prometheusClient, inputValidator, policyPatchValidator);
    }

    @Bean
    TokenSeparationGuard tokenSeparationGuard(MalucaMcpProperties properties) {
        return new TokenSeparationGuard(properties);
    }

    /** The provider intended for an AI agent. It deliberately never receives the apply object. */
    @Bean("agentToolProvider")
    ToolCallbackProvider agentToolProvider(AgentMcpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "maluca.mcp", name = "apply-enabled", havingValue = "true")
    HumanApprovalMcpTools humanApprovalMcpTools(
            TriageClient triageClient,
            PolicyPatchValidator validator,
            MalucaMcpProperties properties) {
        validateApprovalSecurity(properties);
        return new HumanApprovalMcpTools(
                triageClient, validator, properties.security().approvalPrincipal().trim());
    }

    private static void validateApprovalSecurity(MalucaMcpProperties properties) {
        String agent = properties.security().bearerToken();
        String approval = properties.security().approvalBearerToken();
        if (approval == null || approval.isBlank()) {
            throw new IllegalStateException(
                    "maluca.mcp.security.approval-bearer-token is required when apply is enabled");
        }
        if (agent != null && constantTimeEquals(agent, approval)) {
            throw new IllegalStateException("approval bearer token must differ from the agent bearer token");
        }
        if (properties.triageApprovalToken() == null
                || properties.triageApprovalToken().isBlank()) {
            throw new IllegalStateException(
                    "maluca.mcp.triage-approval-token is required when apply is enabled");
        }
        String principal = properties.security().approvalPrincipal();
        if (principal == null || principal.isBlank() || principal.length() > 128
                || principal.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("approval principal must contain 1 to 128 safe characters");
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    /** Fails startup if a public MCP credential is reused for a privileged upstream. */
    static final class TokenSeparationGuard {

        TokenSeparationGuard(MalucaMcpProperties properties) {
            if (properties.promql().queryTimeout().compareTo(properties.prometheus().readTimeout()) > 0) {
                throw new IllegalStateException(
                        "Prometheus query-timeout cannot exceed its HTTP read-timeout");
            }
            String agent = properties.security().bearerToken();
            String human = properties.security().approvalBearerToken();
            String triageApproval = properties.triageApprovalToken();
            rejectReuse("triage auth token", agent, properties.triage().authToken());
            rejectReuse("proxy admin token", agent, properties.proxy().authToken());
            if (properties.applyEnabled()) {
                rejectReuse("triage auth token", human, properties.triage().authToken());
                rejectReuse("proxy admin token", human, properties.proxy().authToken());
                rejectReuse("triage approval token", agent, triageApproval);
                rejectReuse("triage approval token", human, triageApproval);
                rejectReuse("triage approval token", properties.triage().authToken(), triageApproval);
            }
        }

        private static void rejectReuse(String upstreamName, String inbound, String upstream) {
            if (inbound != null && !inbound.isBlank() && upstream != null && !upstream.isBlank()
                    && constantTimeEquals(inbound, upstream)) {
                throw new IllegalStateException(
                        "MCP bearer credentials must differ from the " + upstreamName);
            }
        }
    }
}
