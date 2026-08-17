package com.maluca.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

class BoundedJsonClientTest {

    @Test
    void parsesJsonWithinTheCap() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://triage.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://triage.test/api/v1/incidents"))
                .andRespond(withSuccess("[{\"status\":\"OPEN\"}]", MediaType.APPLICATION_JSON));
        BoundedJsonClient client = new BoundedJsonClient(
                "triage", builder.build(), new ObjectMapper(), 128);

        var result = client.get(uri -> uri.path("/api/v1/incidents").build());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).path("status").asText()).isEqualTo("OPEN");
        server.verify();
    }

    @Test
    void rejectsAResponseBeforeBufferingPastTheConfiguredCap() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://triage.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://triage.test/too-large"))
                .andRespond(withSuccess("{\"value\":\"too large\"}", MediaType.APPLICATION_JSON));
        BoundedJsonClient client = new BoundedJsonClient(
                "triage", builder.build(), new ObjectMapper(), 8);

        assertThatThrownBy(() -> client.get(uri -> uri.path("/too-large").build()))
                .isInstanceOf(UpstreamServiceException.class)
                .hasMessageContaining("exceeded 8 bytes");
        server.verify();
    }

    @Test
    void rejectsRedirectsInsteadOfTreatingThemAsSuccessfulEmptyJson() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://triage.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://triage.test/redirect"))
                .andRespond(withStatus(HttpStatus.FOUND).location(java.net.URI.create("http://other.test")));
        BoundedJsonClient client = new BoundedJsonClient(
                "triage", builder.build(), new ObjectMapper(), 128);

        assertThatThrownBy(() -> client.get(uri -> uri.path("/redirect").build()))
                .isInstanceOf(UpstreamServiceException.class)
                .hasMessageContaining("HTTP 302");
        server.verify();
    }

    @Test
    void rejectsInvalidJsonAndAcceptsAResponseExactlyAtTheCap() {
        RestClient.Builder invalidBuilder = RestClient.builder().baseUrl("http://triage.test");
        MockRestServiceServer invalidServer = MockRestServiceServer.bindTo(invalidBuilder).build();
        invalidServer.expect(requestTo("http://triage.test/invalid"))
                .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));
        BoundedJsonClient invalidClient = new BoundedJsonClient(
                "triage", invalidBuilder.build(), new ObjectMapper(), 8);
        assertThatThrownBy(() -> invalidClient.get(uri -> uri.path("/invalid").build()))
                .isInstanceOf(UpstreamServiceException.class)
                .hasMessageContaining("invalid JSON");

        RestClient.Builder exactBuilder = RestClient.builder().baseUrl("http://triage.test");
        MockRestServiceServer exactServer = MockRestServiceServer.bindTo(exactBuilder).build();
        exactServer.expect(requestTo("http://triage.test/exact"))
                .andRespond(withSuccess("{\"a\":1}", MediaType.APPLICATION_JSON));
        BoundedJsonClient exactClient = new BoundedJsonClient(
                "triage", exactBuilder.build(), new ObjectMapper(), 7);
        assertThat(exactClient.get(uri -> uri.path("/exact").build()).path("a").asInt())
                .isEqualTo(1);
    }
}
