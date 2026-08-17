package com.maluca.triage.agent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import com.maluca.triage.TriageTestFixtures;
import com.maluca.triage.config.TriageProperties;
import com.maluca.triage.runbook.RunbookSearchService;

class TriageAgentDeadlineTest {

    @Test
    void totalOrchestrationDeadlineCancelsWorkBeforeModelInvocation() {
        ChatClient chat = mock(ChatClient.class);
        RunbookSearchService runbooks = mock(RunbookSearchService.class);
        TriageValidationGate gate = mock(TriageValidationGate.class);
        AgentToolProvider tools = mock(AgentToolProvider.class);
        AgentToolProvider.BudgetScope scope = mock(AgentToolProvider.BudgetScope.class);
        TriageProperties properties = shortDeadlineProperties();
        when(tools.openBudget(any(), anyInt())).thenReturn(scope);
        when(runbooks.search(any(), any())).thenAnswer(ignored -> {
            try {
                new CountDownLatch(1).await();
                throw new AssertionError("unreachable");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("retrieval interrupted", interrupted);
            }
        });
        TriageAgent agent = new TriageAgent(chat, runbooks, gate, tools, properties);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    assertThatThrownBy(() -> agent.triage(
                            TriageTestFixtures.incident(), "bounded brief"))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("total deadline"));
        } finally {
            agent.close();
        }
    }

    private static TriageProperties shortDeadlineProperties() {
        TriageProperties base = TriageTestFixtures.properties(Path.of("policies.yml"));
        TriageProperties.Agent original = base.agent();
        TriageProperties.Agent agent = new TriageProperties.Agent(
                true, original.pollInterval(), Duration.ofMillis(10), Duration.ofMillis(60),
                Duration.ofMillis(120), original.maxToolCalls(), original.maxAttempts(),
                original.retryBaseDelay(), original.retryMaxDelay(), original.maxBriefCharacters(),
                original.maxSampleCharacters(), original.maxSampleContributions(), original.model(),
                original.promptVersion(), original.repairAttempts(), original.maxSummaryWords(),
                original.maxEvidenceItems(), original.allowedTools());
        return new TriageProperties(base.security(), base.privacy(), base.ingest(),
                base.detection(), agent, base.retrieval(), base.retention(),
                base.policy(), base.upstreams());
    }
}
