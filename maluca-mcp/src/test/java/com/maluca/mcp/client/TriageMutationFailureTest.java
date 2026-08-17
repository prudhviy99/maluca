package com.maluca.mcp.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maluca.contracts.policy.PolicyPatch;
import com.maluca.contracts.policy.PolicyProposalRequest;
import com.maluca.mcp.TestProperties;

class TriageMutationFailureTest {

    @Test
    void connectionFailureAfterMutationDispatchIsReportedAsIndeterminate() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://triage.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://triage.test/api/v1/proposals"))
                .andRespond(withException(new IOException("response lost")));
        TriageClient client = new TriageClient(
                builder.build(), builder.build(), new ObjectMapper(), TestProperties.defaults());
        var request = new PolicyProposalRequest(
                UUID.fromString("3b6923f7-8f64-4c5e-a708-b71c7c547ee0"),
                new PolicyPatch("login", "/api/login", "OBSERVE", null, null, null,
                        List.of(), List.of(), List.of(), List.of(), null, "Observe safely"));

        assertThatThrownBy(() -> client.proposePolicyPatch(request))
                .isInstanceOf(IndeterminateUpstreamOperationException.class)
                .hasMessageContaining("inspect the incident/proposal audit state");
    }

    @Test
    void successfulMutationWithInvalidJsonIsReportedAsIndeterminate() {
        assertIndeterminate2xx("not-json", MediaType.APPLICATION_JSON);
    }

    @Test
    void successfulMutationWithWrongJsonShapeIsReportedAsIndeterminate() {
        assertIndeterminate2xx("[]", MediaType.APPLICATION_JSON);
    }

    @Test
    void successfulMutationWithIncompleteReceiptIsReportedAsIndeterminate() {
        assertIndeterminate2xx("{}", MediaType.APPLICATION_JSON);
    }

    @Test
    void serverErrorAfterMutationDispatchIsReportedAsIndeterminate() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://triage.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://triage.test/api/v1/proposals"))
                .andRespond(withServerError());
        TriageClient client = new TriageClient(
                builder.build(), builder.build(), new ObjectMapper(), TestProperties.defaults());
        var request = new PolicyProposalRequest(
                UUID.fromString("3b6923f7-8f64-4c5e-a708-b71c7c547ee0"),
                new PolicyPatch("login", "/api/login", "OBSERVE", null, null, null,
                        List.of(), List.of(), List.of(), List.of(), null, "Observe safely"));

        assertThatThrownBy(() -> client.proposePolicyPatch(request))
                .isInstanceOf(IndeterminateUpstreamOperationException.class)
                .hasMessageContaining("outcome is indeterminate");
        server.verify();
    }

    private static void assertIndeterminate2xx(String body, MediaType mediaType) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://triage.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://triage.test/api/v1/proposals"))
                .andRespond(withSuccess(body, mediaType));
        TriageClient client = new TriageClient(
                builder.build(), builder.build(), new ObjectMapper(), TestProperties.defaults());
        var request = new PolicyProposalRequest(
                UUID.fromString("3b6923f7-8f64-4c5e-a708-b71c7c547ee0"),
                new PolicyPatch("login", "/api/login", "OBSERVE", null, null, null,
                        List.of(), List.of(), List.of(), List.of(), null, "Observe safely"));

        assertThatThrownBy(() -> client.proposePolicyPatch(request))
                .isInstanceOf(IndeterminateUpstreamOperationException.class)
                .hasMessageContaining("outcome is indeterminate");
        server.verify();
    }
}
