package com.maluca.triage.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.maluca.contracts.incident.Classification;
import com.maluca.contracts.incident.Confidence;
import com.maluca.contracts.triage.Citation;
import com.maluca.contracts.triage.EvidenceReference;
import com.maluca.contracts.triage.TriageReportView;
import com.maluca.triage.TriageTestFixtures;

class MarkdownReportRendererTest {

    @Test
    void escapesModelControlledMarkdownAndHtml() {
        var report = new TriageReportView(
                UUID.randomUUID(), TriageTestFixtures.incident().id(), Instant.now(),
                "model`name", "v1", Classification.BURST_FLOOD, Confidence.MEDIUM,
                "![track](https://attacker.invalid/pixel)\n# injected <script>alert(1)</script>",
                List.of(new EvidenceReference("[fact]", "120")),
                List.of(new Citation("chunk", "source.md", "Confirm")),
                List.of(), null, true, List.of());

        String markdown = new MarkdownReportRenderer().render(TriageTestFixtures.incident(), report);

        assertThat(markdown).doesNotContain("![track]", "\n# injected", "<script>")
                .contains("\\!\\[track\\]\\(https://attacker\\.invalid/pixel\\)")
                .contains("&lt;script&gt;");
    }
}
