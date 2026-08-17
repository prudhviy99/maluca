package com.maluca.mcp.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "maluca.mcp.security.bearer-token=test-agent-secret",
        "maluca.mcp.security.approval-bearer-token=test-human-secret"
})
@AutoConfigureMockMvc
@AutoConfigureObservability
class SecurityIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void healthIsPublicButOtherActuatorEndpointsAreProtected() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/info")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer test-agent-secret"))
                .andExpect(status().isOk());
    }

    @Test
    void streamableMcpEndpointRequiresTheConfiguredBearerToken() throws Exception {
        mvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/mcp")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer test-agent-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
