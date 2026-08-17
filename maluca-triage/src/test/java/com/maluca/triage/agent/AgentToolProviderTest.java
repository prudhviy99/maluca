package com.maluca.triage.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import com.maluca.triage.TriageTestFixtures;

class AgentToolProviderTest {

    @Test
    void enforcesExactAllowlistAndPerOrchestrationCallBudget() {
        ToolCallback allowed = callback("get_incidents");
        ToolCallback denied = callback("approve_and_apply");
        ToolCallbackProvider source = () -> new ToolCallback[] { allowed, denied };
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        when(providers.orderedStream()).thenAnswer(ignored -> Stream.of(source));
        AgentToolProvider bounded = new AgentToolProvider(
                providers, TriageTestFixtures.properties(Path.of("policies.yml")));

        assertThat(bounded.callbacks()).extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("get_incidents");
        ToolCallback callback = bounded.callbacks().getFirst();
        assertThatThrownBy(() -> callback.call("{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside bounded");

        try (var ignored = bounded.openBudget(Duration.ofSeconds(1), 1)) {
            assertThat(callback.call("{}")).isEqualTo("ok");
            assertThatThrownBy(() -> callback.call("{}"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("budget exceeded");
        }
    }

    @Test
    void configurationCannotEnableMutatingMcpCallbacks() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ToolCallbackProvider> providers = mock(ObjectProvider.class);
        var base = TriageTestFixtures.properties(Path.of("policies.yml"));
        var original = base.agent();
        var unsafeAgent = new com.maluca.triage.config.TriageProperties.Agent(
                original.enabled(), original.pollInterval(), original.inferenceTimeout(),
                original.orchestrationTimeout(), original.leaseTimeout(), original.maxToolCalls(),
                original.maxAttempts(), original.retryBaseDelay(), original.retryMaxDelay(),
                original.maxBriefCharacters(), original.maxSampleCharacters(),
                original.maxSampleContributions(), original.model(), original.promptVersion(),
                original.repairAttempts(), original.maxSummaryWords(), original.maxEvidenceItems(),
                List.of("get_incidents", "propose_policy_patch"));
        var unsafe = new com.maluca.triage.config.TriageProperties(
                base.security(), base.privacy(), base.ingest(), base.detection(), unsafeAgent,
                base.retrieval(), base.retention(), base.policy(), base.upstreams());

        assertThatThrownBy(() -> new AgentToolProvider(providers, unsafe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-read-only")
                .hasMessageContaining("propose_policy_patch");
    }

    private static ToolCallback callback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call("{}")).thenReturn("ok");
        return callback;
    }
}
