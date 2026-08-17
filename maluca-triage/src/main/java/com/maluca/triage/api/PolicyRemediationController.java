package com.maluca.triage.api;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.maluca.contracts.policy.ApprovalRequest;
import com.maluca.contracts.policy.PolicyProposalRequest;
import com.maluca.contracts.policy.PolicyProposalView;
import com.maluca.contracts.policy.PolicyReconciliationRequest;
import com.maluca.contracts.policy.PolicyReconciliationView;
import com.maluca.triage.policy.PolicyRemediationService;
import com.maluca.triage.policy.PolicyProposalRepository;

@RestController
@RequestMapping("/api/v1")
public class PolicyRemediationController {

    private final PolicyRemediationService remediation;
    private final PolicyProposalRepository proposals;

    public PolicyRemediationController(PolicyRemediationService remediation,
                                       PolicyProposalRepository proposals) {
        this.remediation = remediation;
        this.proposals = proposals;
    }

    @GetMapping("/proposals/{id}")
    public PolicyProposalView getProposal(@PathVariable UUID id) {
        return proposals.find(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("proposal not found"));
    }

    @GetMapping("/incidents/{id}/proposals")
    public List<PolicyProposalView> listProposals(@PathVariable UUID id,
                                                  @org.springframework.web.bind.annotation.RequestParam(
                                                          defaultValue = "20") int limit) {
        return proposals.findForIncident(id, Math.max(1, Math.min(100, limit)));
    }

    @PostMapping("/proposals")
    public PolicyProposalView propose(@RequestBody PolicyProposalRequest request, Principal principal) {
        return remediation.propose(request, principal.getName());
    }

    @PostMapping("/incidents/{id}/apply")
    public PolicyProposalView apply(@PathVariable UUID id, @RequestBody ApprovalRequest request,
                                    Principal principal) {
        return remediation.apply(id, request, principal.getName());
    }

    @PostMapping("/incidents/{id}/reconcile-policy")
    public PolicyReconciliationView reconcilePolicy(
            @PathVariable UUID id,
            @RequestBody PolicyReconciliationRequest request,
            Principal principal) {
        return remediation.reconcileIndeterminate(id, request, principal.getName());
    }
}
