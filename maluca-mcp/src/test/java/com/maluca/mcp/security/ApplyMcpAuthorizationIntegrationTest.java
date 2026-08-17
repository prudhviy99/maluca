package com.maluca.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.maluca.mcp.client.TriageClient;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "maluca.mcp.apply-enabled=true",
        "maluca.mcp.security.bearer-token=test-agent-secret",
        "maluca.mcp.security.approval-bearer-token=test-human-secret",
        "maluca.mcp.triage-approval-token=test-triage-operator-secret"
})
class ApplyMcpAuthorizationIntegrationTest {

    @LocalServerPort
    int port;

    @MockitoBean
    TriageClient triageClient;

    @Test
    void agentBearerCannotDiscoverOrInvokeTheHumanApplyCapability() {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp")
                .customizeRequest(request -> request.header(
                        "Authorization", "Bearer test-agent-secret"))
                .build();
        try (var client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(5))
                .build()) {
            client.initialize();
            assertThat(client.listTools().tools())
                    .extracting(McpSchema.Tool::name)
                    .doesNotContain("approve_and_apply");

            assertThatThrownBy(() -> client.callTool(new McpSchema.CallToolRequest(
                    "approve_and_apply", Map.of())))
                    .hasMessageContaining("Unknown tool");
            verifyNoInteractions(triageClient);
        }
    }

    @Test
    void operatorBearerUsesASeparateEndpointThatPublishesOnlyHumanApply() {
        when(triageClient.approveAndApply(any(), any()))
                .thenReturn(JsonNodeFactory.instance.objectNode().put("status", "APPLIED"));
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/operator/mcp")
                .customizeRequest(request -> request.header(
                        "Authorization", "Bearer test-human-secret"))
                .build();
        try (var client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(5))
                .build()) {
            client.initialize();
            assertThat(client.listTools().tools())
                    .extracting(McpSchema.Tool::name)
                    .containsExactly("approve_and_apply");

            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(
                    "approve_and_apply",
                    Map.of(
                            "incidentId", "3b6923f7-8f64-4c5e-a708-b71c7c547ee0",
                            "proposalId", "19c8798d-4d25-4ce6-a6d9-b8aad5bc97f1",
                            "expectedProposalSha256", "b".repeat(64),
                            "expectedPolicySha256", "a".repeat(64),
                            "expectedIncidentVersion", 4)));

            assertThat(result.isError()).isFalse();
        }
    }
}
