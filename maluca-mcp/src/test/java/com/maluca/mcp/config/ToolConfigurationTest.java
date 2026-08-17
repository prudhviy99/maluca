package com.maluca.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.maluca.mcp.TestProperties;
import com.maluca.mcp.client.MalucaProxyClient;
import com.maluca.mcp.client.PrometheusClient;
import com.maluca.mcp.client.TriageClient;
import com.maluca.mcp.tool.HumanApprovalMcpTools;
import com.maluca.mcp.validation.PolicyPatchValidator;
import com.maluca.mcp.validation.ToolInputValidator;

class ToolConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ToolConfiguration.class)
            .withBean(TriageClient.class, () -> mock(TriageClient.class))
            .withBean(MalucaProxyClient.class, () -> mock(MalucaProxyClient.class))
            .withBean(PrometheusClient.class, () -> mock(PrometheusClient.class))
            .withBean(ToolInputValidator.class, () -> mock(ToolInputValidator.class))
            .withBean(PolicyPatchValidator.class, () -> mock(PolicyPatchValidator.class));

    @Test
    void defaultProviderContainsOnlyTheSevenAgentSafeTools() {
        runner.withBean(MalucaMcpProperties.class, TestProperties::defaults)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(HumanApprovalMcpTools.class);
                    assertThat(context).doesNotHaveBean("humanApprovalToolProvider");
                    assertThat(toolNames(context.getBean("agentToolProvider", ToolCallbackProvider.class)))
                            .containsExactlyInAnyOrder(
                                    "get_incidents", "get_decisions", "get_signal_breakdown",
                                    "query_metrics", "list_policies", "search_runbooks",
                                    "propose_policy_patch")
                            .doesNotContain("approve_and_apply");
                });
    }

    @Test
    void applyToolObjectIsNeverAddedToTheAgentProviderWhenExplicitlyEnabled() {
        runner.withPropertyValues("maluca.mcp.apply-enabled=true")
                .withBean(MalucaMcpProperties.class,
                        () -> TestProperties.properties(true, "agent-secret", "human-secret", 1_048_576))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(toolNames(context.getBean("agentToolProvider", ToolCallbackProvider.class)))
                            .doesNotContain("approve_and_apply");
                    assertThat(context).hasSingleBean(HumanApprovalMcpTools.class)
                            .doesNotHaveBean("humanApprovalToolProvider");
                });
    }

    @Test
    void applyModeFailsClosedWithoutADistinctHumanToken() {
        runner.withPropertyValues("maluca.mcp.apply-enabled=true")
                .withBean(MalucaMcpProperties.class,
                        () -> TestProperties.properties(true, "same-secret", "same-secret", 1_048_576))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void publicAgentCredentialCannotBeReusedAsAnUpstreamCredential() {
        runner.withBean(MalucaMcpProperties.class,
                        () -> TestProperties.properties(
                                false, "upstream-secret", "human-secret", 1_048_576))
                .run(context -> assertThat(context).hasFailed());
    }

    private static Set<String> toolNames(ToolCallbackProvider provider) {
        return Arrays.stream(provider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());
    }
}
