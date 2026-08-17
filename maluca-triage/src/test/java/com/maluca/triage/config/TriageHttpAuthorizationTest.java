package com.maluca.triage.config;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.maluca.triage.TriageTestFixtures;
import com.maluca.triage.api.IncidentLifecycleController;
import com.maluca.triage.api.PolicyRemediationController;
import com.maluca.triage.incident.IncidentLifecycleService;
import com.maluca.triage.policy.PolicyProposalRepository;
import com.maluca.triage.policy.PolicyRemediationService;

@WebMvcTest({ IncidentLifecycleController.class, PolicyRemediationController.class })
@Import({ SecurityConfiguration.class,
        SecurityConfiguration.TokenAuthenticationFilter.class,
        TriageHttpAuthorizationTest.PropertiesConfiguration.class })
class TriageHttpAuthorizationTest {

    private static final String INCIDENT = "00000000-0000-0000-0000-000000000123";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    IncidentLifecycleService lifecycle;

    @MockitoBean
    PolicyRemediationService remediation;

    @MockitoBean
    PolicyProposalRepository proposals;

    @Test
    void terminalLifecycleMutationsRejectInternalServiceCredential() throws Exception {
        mvc.perform(post("/api/v1/incidents/" + INCIDENT + "/dismiss")
                        .header("X-Maluca-Internal-Token", "internal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedIncidentVersion\":3,\"reason\":\"reviewed\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/incidents/" + INCIDENT + "/reconcile-policy")
                        .header("X-Maluca-Internal-Token", "internal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reconciliationBody()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(lifecycle, remediation);
    }

    @Test
    void operatorBearerCanReachDismissAndReconciliationControllers() throws Exception {
        mvc.perform(post("/api/v1/incidents/" + INCIDENT + "/dismiss")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedIncidentVersion\":3,\"reason\":\"reviewed\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/incidents/" + INCIDENT + "/reconcile-policy")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reconciliationBody()))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticatedTerminalMutationFailsClosed() throws Exception {
        mvc.perform(post("/api/v1/incidents/" + INCIDENT + "/dismiss")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedIncidentVersion\":3,\"reason\":\"reviewed\"}"))
                .andExpect(status().isUnauthorized());
    }

    private static String reconciliationBody() {
        return """
                {"proposalId":"19c8798d-4d25-4ce6-a6d9-b8aad5bc97f1",
                 "expectedProposalSha256":"%s",
                 "expectedBaselinePolicySha256":"%s",
                 "expectedTargetPolicySha256":"%s",
                 "expectedIncidentVersion":3}
                """.formatted("b".repeat(64), "a".repeat(64), "c".repeat(64));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PropertiesConfiguration {
        @Bean
        TriageProperties triageProperties() {
            return TriageTestFixtures.properties(Path.of("policies.yml"));
        }
    }
}
