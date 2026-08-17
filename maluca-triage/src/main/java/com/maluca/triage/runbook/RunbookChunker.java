package com.maluca.triage.runbook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

/** Splits trusted Markdown at H2 boundaries while preserving source headings. */
@Component
public class RunbookChunker {

    static final int MAX_SOURCE_CHARACTERS = 512;
    static final int MAX_MARKDOWN_CHARACTERS = 256_000;
    static final int MAX_HEADING_CHARACTERS = 256;
    static final int MAX_CHUNK_CHARACTERS = 32_000;
    static final int MAX_CHUNKS_PER_RUNBOOK = 100;

    public List<RunbookChunk> chunk(String source, String markdown) {
        if (source == null || source.isBlank() || markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("source and markdown are required");
        }
        if (source.length() > MAX_SOURCE_CHARACTERS
                || source.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("runbook source is oversized or unsafe");
        }
        if (markdown.length() > MAX_MARKDOWN_CHARACTERS) {
            throw new IllegalArgumentException("runbook exceeds the Markdown character limit: " + source);
        }
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        String title = firstTitle(normalized);
        List<RunbookChunk> chunks = new ArrayList<>();
        String heading = null;
        StringBuilder body = new StringBuilder();
        for (String line : normalized.split("\n", -1)) {
            if (line.startsWith("## ")) {
                add(chunks, source, title, heading, body);
                heading = line.substring(3).trim();
                if (heading.isBlank() || heading.length() > MAX_HEADING_CHARACTERS
                        || heading.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("runbook heading is oversized or unsafe: " + source);
                }
                body.setLength(0);
            } else if (heading != null) {
                body.append(line).append('\n');
            }
        }
        add(chunks, source, title, heading, body);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("runbook contains no H2 sections: " + source);
        }
        if (chunks.size() > MAX_CHUNKS_PER_RUNBOOK) {
            throw new IllegalArgumentException("runbook contains too many sections: " + source);
        }
        return List.copyOf(chunks);
    }

    private static void add(List<RunbookChunk> chunks, String source, String title,
                            String heading, StringBuilder body) {
        if (heading == null) {
            return;
        }
        String trimmed = body.toString().trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("empty section " + source + "#" + heading);
        }
        String headingSlug = slug(heading);
        if (headingSlug.isBlank()) {
            throw new IllegalArgumentException("runbook heading has no stable identifier: " + source);
        }
        String chunkId = source + "#" + headingSlug;
        String content = "# " + title + "\n\n## " + heading + "\n\n" + trimmed;
        if (content.length() > MAX_CHUNK_CHARACTERS) {
            throw new IllegalArgumentException("runbook section exceeds the character limit: " + chunkId);
        }
        if (chunks.stream().anyMatch(existing -> existing.chunkId().equals(chunkId))) {
            throw new IllegalArgumentException("duplicate runbook section ID: " + chunkId);
        }
        chunks.add(new RunbookChunk(chunkId, source, heading, content, sha256(content)));
    }

    private static String firstTitle(String markdown) {
        return markdown.lines()
                .filter(line -> line.startsWith("# "))
                .map(line -> line.substring(2).trim())
                .findFirst()
                .orElse("Maluca incident runbook");
    }

    private static String slug(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
