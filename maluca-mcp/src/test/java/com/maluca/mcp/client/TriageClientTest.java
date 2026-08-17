package com.maluca.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maluca.mcp.TestProperties;
import com.maluca.mcp.config.RestClientConfiguration;
import com.maluca.contracts.policy.ApprovalRequest;

class TriageClientTest {

    @Test
    void forwardsBoundedDecisionFiltersAndInternalToken() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://triage.test")
                .defaultHeader(RestClientConfiguration.INTERNAL_TOKEN_HEADER, "upstream-secret");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://triage.test/api/v1/decisions?limit=200&policy=login&client_key=client_1&action=BLOCK&from=2026-08-12T10:00:00Z&to=2026-08-12T11:00:00Z"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(RestClientConfiguration.INTERNAL_TOKEN_HEADER, "upstream-secret"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        TriageClient client = new TriageClient(
                builder.build(), builder.build(), new ObjectMapper(), TestProperties.defaults());

        var response = client.getDecisions(
                "login", "client_1", "BLOCK",
                Instant.parse("2026-08-12T10:00:00Z"),
                Instant.parse("2026-08-12T11:00:00Z"), 999);

        assertThat(response).isEmpty();
        server.verify();
    }

    @Test
    void usesTheTriageRunbookKContract() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://triage.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://triage.test/api/v1/runbooks/search?query=redis%20degradation&k=12"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        TriageClient client = new TriageClient(
                builder.build(), builder.build(), new ObjectMapper(), TestProperties.defaults());

        client.searchRunbooks("redis degradation", 99);

        server.verify();
    }

    @Test
    void rejectsAnUpstreamListLargerThanTheRequestedLimit() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://triage.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://triage.test/api/v1/incidents?limit=1"))
                .andRespond(withSuccess("[{},{}]", MediaType.APPLICATION_JSON));
        TriageClient client = new TriageClient(
                builder.build(), builder.build(), new ObjectMapper(), TestProperties.defaults());

        assertThatThrownBy(() -> client.getIncidents(null, 1))
                .isInstanceOf(UpstreamServiceException.class)
                .hasMessageContaining("more than 1 items");
        server.verify();
    }

    @Test
    void applyUsesTheDedicatedTriageOperatorBearerNotTheInternalHeader() {
        RestClient.Builder internalBuilder = RestClient.builder()
                .baseUrl("http://triage.test")
                .defaultHeader(RestClientConfiguration.INTERNAL_TOKEN_HEADER, "internal-secret");
        RestClient.Builder approvalBuilder = RestClient.builder()
                .baseUrl("http://triage.test")
                .defaultHeader("Authorization", "Bearer triage-operator-secret");
        MockRestServiceServer approvalServer = MockRestServiceServer.bindTo(approvalBuilder).build();
        UUID incidentId = UUID.fromString("3b6923f7-8f64-4c5e-a708-b71c7c547ee0");
        UUID proposalId = UUID.fromString("19c8798d-4d25-4ce6-a6d9-b8aad5bc97f1");
        approvalServer.expect(requestTo(
                        "http://triage.test/api/v1/incidents/" + incidentId + "/apply"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer triage-operator-secret"))
                .andExpect(request -> assertThat(request.getHeaders()
                        .containsKey(RestClientConfiguration.INTERNAL_TOKEN_HEADER)).isFalse())
                .andRespond(withSuccess("""
                        {"id":"%s","incidentId":"%s","proposalSha256":"%s",
                         "policySha256":"%s","status":"APPLIED"}
                        """.formatted(proposalId, incidentId, "b".repeat(64), "a".repeat(64)),
                        MediaType.APPLICATION_JSON));
        TriageClient client = new TriageClient(
                internalBuilder.build(), approvalBuilder.build(), new ObjectMapper(),
                TestProperties.defaults());

        var result = client.approveAndApply(incidentId,
                new ApprovalRequest(proposalId, "b".repeat(64), "a".repeat(64),
                        4L, "operator@example.test"));

        assertThat(result.path("status").asText()).isEqualTo("APPLIED");
        approvalServer.verify();
    }
}
