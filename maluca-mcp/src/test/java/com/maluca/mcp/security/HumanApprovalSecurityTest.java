package com.maluca.mcp.security;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.maluca.contracts.policy.ApprovalRequest;
import com.maluca.mcp.client.TriageClient;
import com.maluca.mcp.tool.HumanApprovalMcpTools;

@SpringBootTest(properties = {
        "maluca.mcp.apply-enabled=true",
        "maluca.mcp.security.bearer-token=test-agent-secret",
        "maluca.mcp.security.approval-bearer-token=test-human-secret",
        "maluca.mcp.triage-approval-token=test-triage-operator-secret"
})
class HumanApprovalSecurityTest {

    private static final UUID INCIDENT_ID = UUID.fromString("3b6923f7-8f64-4c5e-a708-b71c7c547ee0");
    private static final UUID PROPOSAL_ID = UUID.fromString("19c8798d-4d25-4ce6-a6d9-b8aad5bc97f1");
    private static final String SHA = "a".repeat(64);

    @MockitoBean
    TriageClient triageClient;

    @Autowired
    HumanApprovalMcpTools tools;

    @Test
    @WithMockUser(roles = "AGENT")
    void agentAuthorityCannotApply() {
        assertThatThrownBy(() -> tools.approveAndApply(INCIDENT_ID, PROPOSAL_ID, SHA, SHA, 4L))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(triageClient);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void humanAuthorityCanApplyAValidatedOptimisticRequest() {
        when(triageClient.approveAndApply(any(), any()))
                .thenReturn(JsonNodeFactory.instance.objectNode().put("status", "APPLIED"));

        tools.approveAndApply(INCIDENT_ID, PROPOSAL_ID, SHA, SHA, 4L);

        verify(triageClient).approveAndApply(
                INCIDENT_ID, new ApprovalRequest(PROPOSAL_ID, SHA, SHA, 4L, "maluca-operator"));
    }
}
