package com.maluca.triage.api;

import java.io.IOException;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.maluca.contracts.runbook.RunbookChunkView;
import com.maluca.triage.runbook.RunbookIngestionService;
import com.maluca.triage.runbook.RunbookSearchService;

@RestController
@RequestMapping("/api/v1/runbooks")
public class RunbookController {

    private final RunbookSearchService search;
    private final RunbookIngestionService ingestion;

    public RunbookController(RunbookSearchService search, RunbookIngestionService ingestion) {
        this.search = search;
        this.ingestion = ingestion;
    }

    @GetMapping("/search")
    public List<RunbookChunkView> search(@RequestParam String query,
                                         @RequestParam(required = false) Integer k,
                                         @RequestParam(required = false) Integer limit) {
        return search.search(query, k != null ? k : limit);
    }

    @PostMapping("/ingest")
    public RunbookIngestionService.IngestionResult ingest() throws IOException {
        return ingestion.ingest();
    }
}
