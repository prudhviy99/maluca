package com.maluca.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maluca.mcp.TestProperties;
import com.maluca.mcp.client.MalucaProxyClient;
import com.maluca.mcp.client.PrometheusClient;
import com.maluca.mcp.client.TriageClient;
import com.maluca.mcp.validation.PolicyPatchValidator;
import com.maluca.mcp.validation.ToolInputValidator;
import com.maluca.contracts.policy.PolicyPatch;
import com.maluca.contracts.policy.PolicyProposalRequest;

class AgentMcpToolsCallbackTest {

    private TriageClient triage;
    private ToolCallback getIncidents;

    @BeforeEach
    void setUp() {
        triage = mock(TriageClient.class);
        var properties = TestProperties.defaults();
        AgentMcpTools tools = new AgentMcpTools(
                triage, mock(MalucaProxyClient.class), mock(PrometheusClient.class),
                new ToolInputValidator(properties), new PolicyPatchValidator(properties));
        getIncidents = Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(tools).build()
                        .getToolCallbacks())
                .filter(callback -> callback.getToolDefinition().name().equals("get_incidents"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void callbackSchemaPreservesNamedOptionalArgumentsAndInvokesTheAdapter() {
        when(triage.getIncidents("OPEN", 2))
                .thenReturn(JsonNodeFactory.instance.arrayNode());

        String result = getIncidents.call("{\"status\":\"open\",\"limit\":2}");

        assertThat(result).isEqualTo("[]");
        assertThat(getIncidents.getToolDefinition().inputSchema())
                .contains("status", "limit")
                .doesNotContain("\"required\":[\"status\"");
        verify(triage).getIncidents("OPEN", 2);
    }

    @Test
    void callbackEnforcesConfiguredResultLimitBeforeAnyUpstreamCall() {
        assertThatThrownBy(() -> getIncidents.call("{\"limit\":201}"))
                .hasMessageContaining("limit must be between 1 and 200");
    }

    @Test
    void policyPatchSchemaDoesNotRequireSemanticallyOptionalFields() throws Exception {
        // Keep this assertion close to the callback: schema regressions can make a valid tool
        // impossible for an MCP client to call before Java validation ever runs.
        AgentMcpTools tools = new AgentMcpTools(
                triage, mock(MalucaProxyClient.class), mock(PrometheusClient.class),
                new ToolInputValidator(TestProperties.defaults()),
                new PolicyPatchValidator(TestProperties.defaults()));
        ToolCallback proposal = Arrays.stream(
                        MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks())
                .filter(callback -> callback.getToolDefinition().name().equals("propose_policy_patch"))
                .findFirst()
                .orElseThrow();

        var schema = new ObjectMapper().readTree(proposal.getToolDefinition().inputSchema());
        var required = StreamSupport.stream(
                        schema.path("properties").path("patch").path("required").spliterator(), false)
                .map(node -> node.asText())
                .toList();
        assertThat(required).doesNotContain("mode", "keying", "rateLimit", "bands",
                "addAllowlist", "removeAllowlist", "addDenylist", "removeDenylist", "failMode");
    }

    @Test
    void proposalCallbackConvertsAnOmittedOptionalDeltaToTheSharedWireContract() {
        UUID incidentId = UUID.fromString("3b6923f7-8f64-4c5e-a708-b71c7c547ee0");
        PolicyPatch expectedPatch = new PolicyPatch(
                "login", "/api/login", "OBSERVE", null, null, null,
                List.of(), List.of(), List.of(), List.of(), null,
                "Observe a safer limit before enforcement.");
        when(triage.proposePolicyPatch(new PolicyProposalRequest(incidentId, expectedPatch)))
                .thenReturn(JsonNodeFactory.instance.objectNode().put("status", "PROPOSED"));
        AgentMcpTools tools = new AgentMcpTools(
                triage, mock(MalucaProxyClient.class), mock(PrometheusClient.class),
                new ToolInputValidator(TestProperties.defaults()),
                new PolicyPatchValidator(TestProperties.defaults()));
        ToolCallback proposal = Arrays.stream(
                        MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks())
                .filter(callback -> callback.getToolDefinition().name().equals("propose_policy_patch"))
                .findFirst()
                .orElseThrow();

        String result = proposal.call("""
                {
                  "incidentId": "3b6923f7-8f64-4c5e-a708-b71c7c547ee0",
                  "patch": {
                    "policyName": "login",
                    "route": "/api/login",
                    "mode": "observe",
                    "rationale": "Observe a safer limit before enforcement."
                  }
                }
                """);

        assertThat(result).contains("PROPOSED");
        verify(triage).proposePolicyPatch(new PolicyProposalRequest(incidentId, expectedPatch));
    }
}
