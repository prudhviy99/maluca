package com.maluca.triage.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.maluca.contracts.incident.Classification;
import com.maluca.contracts.incident.IncidentStats;
import com.maluca.contracts.incident.IncidentStatus;
import com.maluca.contracts.incident.IncidentTrigger;
import com.maluca.contracts.incident.IncidentView;
import com.maluca.contracts.policy.PolicyPatch;
import com.maluca.contracts.runbook.RunbookChunkView;
import com.maluca.contracts.triage.TriageResult;
import com.maluca.triage.TriageTestFixtures;
import com.maluca.triage.agent.AgentToolProvider;
import com.maluca.triage.agent.TriageAgent;
import com.maluca.triage.agent.TriageValidationGate;
import com.maluca.triage.policy.PolicyPatchValidator;
import com.maluca.triage.runbook.RunbookSearchService;

/**
 * Frozen evaluation-contract checks plus the explicit, opt-in Ollama run. Invoke
 * the live method with {@code ./gradlew :maluca-triage:llmTest}; ordinary CI
 * excludes only that tagged method and still validates the manifest.
 */
class OllamaRegressionTest {

    private static final Duration MAX_INFERENCE_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration MAX_EVALUATION_TIMEOUT = Duration.ofHours(4);
    private static final Set<String> PATCH_FIELDS = Set.of(
            "mode", "keying", "rateLimit", "bands", "addAllowlist", "removeAllowlist",
            "addDenylist", "removeDenylist", "failMode");

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void committedEvaluationContractIsInternallyConsistentWithoutOllama() throws Exception {
        Baseline baseline = baseline();
        List<Fixture> fixtures = fixtures();

        assertThat(baseline.promptVersion()).isEqualTo("v4");
        assertThat(baseline.defaultModel()).isNotBlank();
        assertThat(baseline.embeddingModel()).isNotBlank();
        assertThat(baseline.fixtureCount()).isEqualTo(fixtures.size());
        assertThat(baseline.repetitions()).isPositive();
        assertThat(baseline.minimumPassRate()).isBetween(0.0, 1.0);
        assertThat(baseline.thresholdKind()).isEqualTo("acceptance-threshold");
        assertThat(baseline.measuredPassRate()).isNull();
        assertThat(baseline.lastMeasuredAt()).isNull();
        assertThat(fixtures).extracting(Fixture::scenario).doesNotHaveDuplicates();
        assertThat(fixtures).extracting(Fixture::classification).doesNotHaveDuplicates();
        assertThat(fixtures).extracting(Fixture::patchExpectation)
                .contains(PatchExpectation.REQUIRED_SCOPED, PatchExpectation.FORBIDDEN);

        for (Fixture fixture : fixtures) {
            assertThat(fixture.scenario()).isNotBlank();
            assertThat(fixture.policyName()).isNotBlank();
            assertThat(fixture.route()).isNotBlank();
            assertThat(fixture.brief()).isNotBlank();
            assertThat(fixture.source()).endsWith(".md");
            assertThat(fixture.heading()).isNotBlank();
            assertThat(fixture.runbook()).isNotBlank();
            assertThat(fixture.chunkId()).startsWith(fixture.source() + "#");
            assertThat(fixture.expectedPatchFields()).doesNotHaveDuplicates();
            assertThat(PATCH_FIELDS).containsAll(fixture.expectedPatchFields());
            if (fixture.patchExpectation() == PatchExpectation.REQUIRED_SCOPED) {
                assertThat(fixture.expectedPatchFields()).isNotEmpty();
            } else {
                assertThat(fixture.expectedPatchFields()).isEmpty();
            }
        }
    }

    @Test
    @Tag("llm")
    void repeatedFixtureScoreMeetsCommittedAcceptanceThreshold() throws Exception {
        Baseline baseline = baseline();
        List<Fixture> fixtures = fixtures();
        EvaluationSettings settings = EvaluationSettings.from(baseline);

        assertTimeoutPreemptively(settings.wholeTestTimeout(), () -> runEvaluation(baseline, fixtures, settings),
                () -> "Ollama evaluation exceeded MALUCA_EVAL_TIMEOUT=" + settings.wholeTestTimeout());
    }

    private void runEvaluation(Baseline baseline, List<Fixture> fixtures, EvaluationSettings settings) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(settings.inferenceTimeout());
        requestFactory.setReadTimeout(settings.inferenceTimeout());
        OllamaApi api = OllamaApi.builder()
                .baseUrl(settings.baseUrl())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(settings.model())
                .temperature(0.0)
                .seed(settings.seed())
                .numCtx(settings.contextSize())
                .disableThinking()
                .build();
        RetryTemplate retryTemplate = RetryTemplate.builder()
                .maxAttempts(2)
                .retryOn(TransientAiException.class)
                .retryOn(ResourceAccessException.class)
                .exponentialBackoff(Duration.ofSeconds(2), 5, Duration.ofMinutes(3))
                .build();
        ChatClient chat = ChatClient.create(OllamaChatModel.builder()
                .ollamaApi(api)
                .defaultOptions(options)
                .retryTemplate(retryTemplate)
                .build());

        var properties = TriageTestFixtures.properties(Path.of("policies.yml"));
        var gate = new TriageValidationGate(new PolicyPatchValidator(properties), properties);
        int passed = 0;
        int total = fixtures.size() * settings.repetitions();
        List<String> failures = new ArrayList<>();
        for (int repetition = 0; repetition < settings.repetitions(); repetition++) {
            for (Fixture fixture : fixtures) {
                RunbookSearchService runbooks = mock(RunbookSearchService.class);
                RunbookChunkView chunk = new RunbookChunkView(
                        fixture.chunkId(), fixture.source(), fixture.heading(), fixture.runbook(), .95);
                when(runbooks.search(anyString(), isNull())).thenReturn(List.of(chunk));
                AgentToolProvider tools = mock(AgentToolProvider.class);
                when(tools.callbacks()).thenReturn(List.of());
                try (TriageAgent agent = new TriageAgent(chat, runbooks, gate, tools, properties)) {
                    var output = agent.triage(incident(fixture), fixture.brief());
                    PatchAssessment patch = assessPatch(fixture, output.result());
                    boolean correct = output.valid()
                            && output.result().classification() == fixture.classification()
                            && output.result().citations().stream()
                                    .anyMatch(citation -> fixture.chunkId().equals(citation.chunkId()))
                            && patch.correct();
                    if (correct) {
                        passed++;
                    } else {
                        failures.add(fixture.scenario() + " repetition=" + repetition
                                + " classification=" + output.result().classification()
                                + " valid=" + output.valid()
                                + " patch=" + patch.detail()
                                + " errors=" + output.validationErrors()
                                + " raw=" + boundedDiagnostic(output.rawResponse()));
                    }
                }
            }
        }
        double passRate = total == 0 ? 0 : (double) passed / total;
        System.out.printf(
                "Ollama regression: model=%s passed=%d total=%d passRate=%.3f minimum=%.3f%n",
                settings.model(), passed, total, passRate, baseline.minimumPassRate());
        failures.forEach(failure -> System.out.println("Ollama regression miss: " + failure));
        assertThat(passRate)
                .withFailMessage("Ollama regression: passRate=%.3f minimum=%.3f failures=%s",
                        passRate, baseline.minimumPassRate(), failures)
                .isGreaterThanOrEqualTo(baseline.minimumPassRate());
    }

    private static String boundedDiagnostic(String value) {
        if (value == null) {
            return "<null>";
        }
        String singleLine = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return singleLine.substring(0, Math.min(2_000, singleLine.length()));
    }

    private static PatchAssessment assessPatch(Fixture fixture, TriageResult result) {
        PolicyPatch patch = result.proposedPatch();
        if (fixture.patchExpectation() == PatchExpectation.FORBIDDEN) {
            return new PatchAssessment(patch == null,
                    patch == null ? "absent as required" : "unexpected patch");
        }
        if (patch == null) {
            boolean optional = fixture.patchExpectation() == PatchExpectation.OPTIONAL_SCOPED;
            return new PatchAssessment(optional, optional ? "absent (optional)" : "required patch absent");
        }
        if (!fixture.policyName().equals(patch.policyName()) || !fixture.route().equals(patch.route())) {
            return new PatchAssessment(false, "scope mismatch policy=" + patch.policyName()
                    + " route=" + patch.route());
        }
        if (fixture.patchExpectation() == PatchExpectation.REQUIRED_SCOPED
                && fixture.expectedPatchFields().stream().noneMatch(field -> patchSets(field, patch))) {
            return new PatchAssessment(false,
                    "required remediation field absent; expected one of " + fixture.expectedPatchFields());
        }
        return new PatchAssessment(true, "scoped patch");
    }

    private static boolean patchSets(String field, PolicyPatch patch) {
        return switch (field) {
            case "mode" -> patch.mode() != null;
            case "keying" -> patch.keying() != null;
            case "rateLimit" -> patch.rateLimit() != null;
            case "bands" -> patch.bands() != null;
            case "addAllowlist" -> !patch.addAllowlist().isEmpty();
            case "removeAllowlist" -> !patch.removeAllowlist().isEmpty();
            case "addDenylist" -> !patch.addDenylist().isEmpty();
            case "removeDenylist" -> !patch.removeDenylist().isEmpty();
            case "failMode" -> patch.failMode() != null;
            default -> false;
        };
    }

    private IncidentView incident(Fixture fixture) {
        Instant now = Instant.parse("2026-08-12T12:00:00Z");
        IncidentStats stats = new IncidentStats(now.minusSeconds(60), now, 100, 50, .5, .05,
                70, 99, 10, 5, Map.of("BLOCK", 50L), Map.of("burst_10s", 20.0), List.of(), List.of());
        return new IncidentView(UUID.nameUUIDFromBytes(fixture.scenario().getBytes()), now, null,
                fixture.policyName(), fixture.route(), IncidentTrigger.MITIGATION_SPIKE,
                IncidentStatus.TRIAGING, stats, 1, now, 1, null, null);
    }

    private Baseline baseline() throws Exception {
        try (InputStream stream = resource("/evals/baseline.json")) {
            return json.readValue(stream, Baseline.class);
        }
    }

    private List<Fixture> fixtures() throws Exception {
        try (InputStream stream = resource("/evals/incidents.json")) {
            return json.readValue(stream, new TypeReference<>() { });
        }
    }

    private InputStream resource(String name) {
        InputStream stream = getClass().getResourceAsStream(name);
        if (stream == null) {
            throw new IllegalStateException("missing resource " + name);
        }
        return stream;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int boundedPositiveIntEnv(String name, int fallback, int maximum) {
        String raw = env(name, String.valueOf(fallback));
        try {
            int value = Integer.parseInt(raw);
            if (value <= 0 || value > maximum) {
                throw new IllegalArgumentException(
                        name + " must be between 1 and " + maximum);
            }
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be a positive integer, got: " + raw, error);
        }
    }

    private static Duration durationEnv(String name, String fallback, Duration maximum) {
        String raw = env(name, fallback);
        final Duration value;
        try {
            value = DurationStyle.detectAndParse(raw);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(name + " must be a duration such as 90s or 30m, got: " + raw,
                    error);
        }
        if (value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " must be positive and no greater than " + maximum);
        }
        return value;
    }

    private record Baseline(
            String promptVersion,
            String defaultModel,
            String embeddingModel,
            int fixtureCount,
            int repetitions,
            double minimumPassRate,
            String thresholdKind,
            Double measuredPassRate,
            Instant lastMeasuredAt,
            String scoring) {
    }

    private record Fixture(
            String scenario,
            Classification classification,
            String policyName,
            String route,
            String brief,
            String chunkId,
            String source,
            String heading,
            String runbook,
            PatchExpectation patchExpectation,
            List<String> expectedPatchFields) {

        private Fixture {
            expectedPatchFields = expectedPatchFields == null ? List.of() : List.copyOf(expectedPatchFields);
        }
    }

    private enum PatchExpectation {
        OPTIONAL_SCOPED,
        REQUIRED_SCOPED,
        FORBIDDEN
    }

    private record PatchAssessment(boolean correct, String detail) {
    }

    private record EvaluationSettings(
            String baseUrl,
            String model,
            int repetitions,
            int contextSize,
            int seed,
            Duration inferenceTimeout,
            Duration wholeTestTimeout) {

        private static EvaluationSettings from(Baseline baseline) {
            int repetitions = boundedPositiveIntEnv(
                    "MALUCA_EVAL_REPETITIONS", baseline.repetitions(), 20);
            int contextSize = boundedPositiveIntEnv("OLLAMA_CONTEXT_SIZE", 8192, 131_072);
            int seed;
            try {
                seed = Integer.parseInt(env("OLLAMA_SEED", "42"));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("OLLAMA_SEED must be an integer", error);
            }
            Duration inferenceTimeout = durationEnv(
                    "OLLAMA_INFERENCE_TIMEOUT", "90s", MAX_INFERENCE_TIMEOUT);
            Duration wholeTestTimeout = durationEnv(
                    "MALUCA_EVAL_TIMEOUT", "30m", MAX_EVALUATION_TIMEOUT);
            if (wholeTestTimeout.compareTo(inferenceTimeout) <= 0) {
                throw new IllegalArgumentException(
                        "MALUCA_EVAL_TIMEOUT must exceed OLLAMA_INFERENCE_TIMEOUT");
            }
            return new EvaluationSettings(
                    env("OLLAMA_BASE_URL", "http://localhost:11434"),
                    env("OLLAMA_CHAT_MODEL", baseline.defaultModel()), repetitions,
                    contextSize, seed, inferenceTimeout, wholeTestTimeout);
        }
    }
}
