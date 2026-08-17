package com.maluca.triage.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.maluca.contracts.incident.IncidentStatus;
import com.maluca.contracts.incident.IncidentView;
import com.maluca.triage.incident.IncidentRepository;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    private final IncidentRepository repository;

    public IncidentController(IncidentRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<IncidentView> list(@RequestParam(required = false) IncidentStatus status,
                                   @RequestParam(defaultValue = "50") int limit) {
        return repository.findRecent(status, Math.max(1, Math.min(100, limit)));
    }

    @GetMapping("/{id}")
    public IncidentView get(@PathVariable UUID id) {
        return repository.find(id).orElseThrow(() -> new java.util.NoSuchElementException("incident not found"));
    }
}
