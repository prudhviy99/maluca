package com.maluca.triage.api;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.maluca.contracts.triage.TriageReportView;
import com.maluca.triage.agent.IncidentTriageWorker;
import com.maluca.triage.incident.IncidentRepository;
import com.maluca.triage.report.MarkdownReportRenderer;
import com.maluca.triage.report.TriageReportRepository;

@RestController
@RequestMapping("/api/v1/incidents/{incidentId}")
public class TriageReportController {

    private final IncidentRepository incidents;
    private final TriageReportRepository reports;
    private final MarkdownReportRenderer markdown;
    private final IncidentTriageWorker worker;

    public TriageReportController(IncidentRepository incidents, TriageReportRepository reports,
                                  MarkdownReportRenderer markdown, IncidentTriageWorker worker) {
        this.incidents = incidents;
        this.reports = reports;
        this.markdown = markdown;
        this.worker = worker;
    }

    @PostMapping("/triage")
    public TriageReportView triage(@PathVariable UUID incidentId) {
        worker.triage(incidentId);
        return report(incidentId);
    }

    @GetMapping("/report")
    public TriageReportView report(@PathVariable UUID incidentId) {
        return reports.findForIncident(incidentId)
                .orElseThrow(() -> new java.util.NoSuchElementException("triage report not found"));
    }

    @GetMapping(value = "/report.md", produces = "text/markdown")
    public String reportMarkdown(@PathVariable UUID incidentId) {
        var incident = incidents.find(incidentId)
                .orElseThrow(() -> new java.util.NoSuchElementException("incident not found"));
        return markdown.render(incident, report(incidentId));
    }
}
