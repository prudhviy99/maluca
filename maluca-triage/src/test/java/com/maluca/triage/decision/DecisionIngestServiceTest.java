package com.maluca.triage.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.maluca.contracts.decision.DecisionBatch;
import com.maluca.contracts.decision.DecisionEvent;
import com.maluca.triage.TriageTestFixtures;
import com.maluca.triage.privacy.ClientKeyPseudonymizer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DecisionIngestServiceTest {

    @Test
    void pseudonymizesClientAndCapsAttackerControlledPath() {
        DecisionRepository repository = mock(DecisionRepository.class);
        when(repository.insertBatch(anyList())).thenReturn(1);
        var properties = TriageTestFixtures.properties(Path.of("policies.yml"));
        var service = new DecisionIngestService(repository, new ClientKeyPseudonymizer(properties),
                properties, new SimpleMeterRegistry());
        String longPath = "/" + "x".repeat(200);

        var result = service.ingest(new DecisionBatch(List.of(event(longPath, "192.0.2.1"))));

        assertThat(result.inserted()).isOne();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DecisionEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).insertBatch(captor.capture());
        DecisionEvent stored = captor.getValue().getFirst();
        assertThat(stored.clientKey()).startsWith("client_").doesNotContain("192.0.2.1");
        assertThat(stored.path()).hasSize(64);
    }

    @Test
    void rejectsOversizedBatchAndInvalidActions() {
        DecisionRepository repository = mock(DecisionRepository.class);
        var properties = TriageTestFixtures.properties(Path.of("policies.yml"));
        var service = new DecisionIngestService(repository, new ClientKeyPseudonymizer(properties),
                properties, new SimpleMeterRegistry());
        List<DecisionEvent> tooMany = java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> event("/", "client-" + i)).toList();
        assertThatThrownBy(() -> service.ingest(new DecisionBatch(tooMany)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("more than 10");

        DecisionEvent invalid = new DecisionEvent(UUID.randomUUID(), Instant.now(), "client", "GET", "/",
                "default", "/**", "ENFORCE", "standard", "EXECUTE_SHELL", "ALLOW", 0,
                "bad", Map.of(), false, "");
        assertThatThrownBy(() -> service.ingest(new DecisionBatch(List.of(invalid))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported action");
    }

    @Test
    void rejectsUnboundedFieldsAndNonFiniteContributions() {
        DecisionRepository repository = mock(DecisionRepository.class);
        var properties = TriageTestFixtures.properties(Path.of("policies.yml"));
        var service = new DecisionIngestService(repository, new ClientKeyPseudonymizer(properties),
                properties, new SimpleMeterRegistry());
        DecisionEvent oversizedPolicy = new DecisionEvent(UUID.randomUUID(), Instant.now(), "client", "GET", "/",
                "x".repeat(129), "/**", "ENFORCE", "standard", "ALLOW", "ALLOW", 0,
                "score_band", Map.of(), false, "");
        assertThatThrownBy(() -> service.ingest(new DecisionBatch(List.of(oversizedPolicy))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("policyName");

        DecisionEvent nonFinite = new DecisionEvent(UUID.randomUUID(), Instant.now(), "client", "GET", "/",
                "default", "/**", "ENFORCE", "standard", "ALLOW", "ALLOW", 0,
                "score_band", Map.of("burst", Double.POSITIVE_INFINITY), false, "");
        assertThatThrownBy(() -> service.ingest(new DecisionBatch(List.of(nonFinite))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finite");

        DecisionEvent enormous = new DecisionEvent(UUID.randomUUID(), Instant.now(), "client", "GET", "/",
                "default", "/**", "ENFORCE", "standard", "ALLOW", "ALLOW", 0,
                "score_band", Map.of("burst", 1_000_000_001d), false, "");
        assertThatThrownBy(() -> service.ingest(new DecisionBatch(List.of(enormous))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("between 0");

        DecisionEvent controlCharacter = new DecisionEvent(UUID.randomUUID(), Instant.now(), "client", "GET", "/",
                "default\nforged-log", "/**", "ENFORCE", "standard", "ALLOW", "ALLOW", 0,
                "score_band", Map.of(), false, "");
        assertThatThrownBy(() -> service.ingest(new DecisionBatch(List.of(controlCharacter))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("control characters");
    }

    private static DecisionEvent event(String path, String client) {
        return new DecisionEvent(UUID.randomUUID(), Instant.now(), client, "GET", path,
                "api", "/api/**", "ENFORCE", "standard", "BLOCK", "BLOCK", 90,
                "score_band", Map.of("burst_10s", 40.0), false, "trace");
    }
}
