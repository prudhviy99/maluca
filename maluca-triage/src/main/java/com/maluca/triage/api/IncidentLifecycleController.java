package com.maluca.triage.api;

import java.security.Principal;
import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.maluca.contracts.incident.IncidentDismissRequest;
import com.maluca.contracts.incident.IncidentView;
import com.maluca.triage.incident.IncidentLifecycleService;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentLifecycleController {

    private final IncidentLifecycleService lifecycle;

    public IncidentLifecycleController(IncidentLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @PostMapping("/{id}/dismiss")
    public IncidentView dismiss(@PathVariable UUID id,
                                @RequestBody IncidentDismissRequest request,
                                Principal principal) {
        return lifecycle.dismissTriageFailure(id, request, principal.getName());
    }
}
