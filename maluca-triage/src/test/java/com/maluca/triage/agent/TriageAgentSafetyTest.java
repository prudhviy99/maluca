package com.maluca.triage.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import com.maluca.contracts.incident.Classification;
import com.maluca.contracts.runbook.RunbookChunkView;
import com.maluca.triage.TriageTestFixtures;
import com.maluca.triage.policy.PolicyPatchValidator;
import com.maluca.triage.runbook.RunbookSearchService;

class TriageAgentSafetyTest {

    @Test
    void discardsInvalidOptionalPatchWithoutDiscardingGroundedDiagnosis() {
        String raw = """
                {
                  "classification":"BURST_FLOOD",
                  "confidence":"HIGH",
                  "summary":"A concentrated burst affected the API policy.",
                  "evidence":[
                    {"fact":"totalDecisions","value":"120"},
                    {"fact":"policy","value":"/api/**"}
                  ],
                  "citations":[{
                    "chunkId":"burst-flood.md#confirm",
                    "source":"burst-flood.md",
                    "heading":"Confirm"
                  }],
                  "proposedPatch":{
                    "policyName":"api",
                    "route":"/api/**",
                    "keying":"CLIENT_IP",
                    "rationale":"Invalid model-proposed keying value"
                  }
                }
                """;
        ChatClient chat = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chat.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn(raw);
        RunbookSearchService runbooks = mock(RunbookSearchService.class);
        when(runbooks.search(anyString(), isNull())).thenReturn(List.of(new RunbookChunkView(
                "burst-flood.md#confirm", "burst-flood.md", "Confirm", "Confirm burst traffic", .9)));
        AgentToolProvider tools = mock(AgentToolProvider.class);
        when(tools.callbacks()).thenReturn(List.of());
        when(tools.openBudget(org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(mock(AgentToolProvider.BudgetScope.class));
        var properties = TriageTestFixtures.properties(Path.of("policies.yml"));
        var gate = new TriageValidationGate(new PolicyPatchValidator(properties), properties);

        try (TriageAgent agent = new TriageAgent(chat, runbooks, gate, tools, properties)) {
            var output = agent.triage(
                    TriageTestFixtures.incident(), "totalDecisions=120 policy=api route=/api/**");

            assertThat(output.valid()).isTrue();
            assertThat(output.result().classification()).isEqualTo(Classification.BURST_FLOOD);
            assertThat(output.result().evidence()).hasSize(1);
            assertThat(output.result().proposedPatch()).isNull();
            assertThat(output.rawResponse()).isEqualTo(raw);
        }
    }
}
