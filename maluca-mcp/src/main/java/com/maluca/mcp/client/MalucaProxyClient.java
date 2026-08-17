package com.maluca.mcp.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maluca.mcp.config.MalucaMcpProperties;

@Component
public class MalucaProxyClient {

    private final BoundedJsonClient http;
    private final int maxPolicies;

    public MalucaProxyClient(
            @Qualifier("proxyRestClient") RestClient restClient,
            ObjectMapper objectMapper,
            MalucaMcpProperties properties) {
        this.http = new BoundedJsonClient("maluca-proxy", restClient, objectMapper,
                properties.proxy().maxResponseBytes());
        this.maxPolicies = properties.limits().maxResultLimit();
    }

    public JsonNode listPolicies() {
        JsonNode result = JsonResultLimits.requireObject("list_policies",
                http.get(uri -> uri.path("/_maluca/admin/policies").build()));
        JsonNode policies = result.path("policies");
        if (!policies.isArray() || policies.size() > maxPolicies) {
            throw new UpstreamServiceException("list_policies", 0,
                    "list_policies returned an invalid or oversized policies array");
        }
        return result;
    }
}
