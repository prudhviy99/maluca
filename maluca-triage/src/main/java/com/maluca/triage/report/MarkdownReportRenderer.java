package com.maluca.triage.report;

import org.springframework.stereotype.Component;

import com.maluca.contracts.incident.IncidentView;
import com.maluca.contracts.triage.TriageReportView;

@Component
public class MarkdownReportRenderer {

    public String render(IncidentView incident, TriageReportView report) {
        StringBuilder markdown = new StringBuilder()
                .append("# Maluca incident ").append(incident.id()).append("\n\n")
                .append("- Status: `").append(incident.status()).append("`\n")
                .append("- Policy: `").append(inline(incident.policyName())).append("` (`")
                .append(inline(incident.policyRoute())).append("`)\n")
                .append("- Trigger: `").append(incident.trigger()).append("`\n")
                .append("- Classification: `").append(report.classification()).append("`\n")
                .append("- Confidence: `").append(report.confidence()).append("`\n")
                .append("- Model: `").append(inline(report.model())).append("`\n")
                .append("- Prompt: `").append(inline(report.promptVersion())).append("`\n")
                .append("- Validation: `").append(report.valid() ? "PASSED" : "FALLBACK").append("`\n\n")
                .append("## Summary\n\n").append(text(report.summary())).append("\n\n")
                .append("## Evidence\n\n");
        if (report.evidence().isEmpty()) {
            markdown.append("No model evidence was accepted.\n");
        } else {
            report.evidence().forEach(value -> markdown.append("- ")
                    .append(text(value.fact())).append(": `").append(inline(value.value())).append("`\n"));
        }
        markdown.append("\n## Runbook citations\n\n");
        if (report.citations().isEmpty()) {
            markdown.append("No citations were accepted.\n");
        } else {
            report.citations().forEach(citation -> markdown.append("- `")
                    .append(inline(citation.chunkId())).append("` — ")
                    .append(text(citation.source())).append(" / ").append(text(citation.heading())).append("\n"));
        }
        markdown.append("\n## Proposed policy change\n\n");
        if (report.proposedPatch() == null) {
            markdown.append("No policy change proposed.\n");
        } else {
            markdown.append("A typed change is available through the report API. It has not been approved or applied.\n")
                    .append("\n- Policy: `").append(inline(report.proposedPatch().policyName())).append("`\n")
                    .append("- Route: `").append(inline(report.proposedPatch().route())).append("`\n")
                    .append("- Rationale: ").append(text(report.proposedPatch().rationale())).append("\n");
        }
        if (!report.validationErrors().isEmpty()) {
            markdown.append("\n## Validation failures\n\n");
            report.validationErrors().forEach(error -> markdown.append("- ").append(text(error)).append("\n"));
        }
        return markdown.toString();
    }

    private static String text(String value) {
        if (value == null) {
            return "";
        }
        String escaped = html(value);
        StringBuilder result = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char character = escaped.charAt(i);
            if ("\\`*_{}[]()#+-.!|>".indexOf(character) >= 0) {
                result.append('\\');
            }
            result.append(character);
        }
        return result.toString();
    }

    private static String inline(String value) {
        return html(value == null ? "" : value).replace("`", "'")
                .replace("\r", " ").replace("\n", " ");
    }

    private static String html(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
