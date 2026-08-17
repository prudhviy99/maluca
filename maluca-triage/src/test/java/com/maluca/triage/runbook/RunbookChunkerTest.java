package com.maluca.triage.runbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class RunbookChunkerTest {

    private final RunbookChunker chunker = new RunbookChunker();

    @Test
    void everyTrustedRunbookProducesFiveStableHeadingChunks() throws Exception {
        for (String source : List.of("burst-flood.md", "distributed-flood.md", "path-scan.md",
                "credential-stuffing.md", "low-and-slow.md", "redis-degradation.md",
                "false-positive-wave.md")) {
            String markdown = new ClassPathResource("runbooks/" + source)
                    .getContentAsString(StandardCharsets.UTF_8);
            var chunks = chunker.chunk(source, markdown);
            assertThat(chunks).extracting(RunbookChunk::heading)
                    .containsExactly("Symptoms", "Confirm", "Remediate", "False-positive checks", "Rollback");
            assertThat(chunks).allSatisfy(chunk -> {
                assertThat(chunk.chunkId()).startsWith(source + "#");
                assertThat(chunk.sha256()).hasSize(64);
                assertThat(chunk.content()).contains("## " + chunk.heading());
            });
        }
    }

    @Test
    void rejectsEmptySections() {
        assertThatThrownBy(() -> chunker.chunk("bad.md", "# Bad\n\n## Symptoms\n\n## Confirm\ntext"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty section");
    }

    @Test
    void rejectsDuplicateStableSectionIdentifiers() {
        assertThatThrownBy(() -> chunker.chunk("bad.md", """
                # Bad

                ## False positive
                first

                ## False-positive
                second
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate runbook section ID");
    }

    @Test
    void rejectsOversizedMarkdownBeforeChunking() {
        String markdown = "# Too large\n\n## Confirm\n" +
                "x".repeat(RunbookChunker.MAX_MARKDOWN_CHARACTERS);

        assertThatThrownBy(() -> chunker.chunk("large.md", markdown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Markdown character limit");
    }
}
