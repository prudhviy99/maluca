package com.maluca.mcp.config;

import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maluca.mcp.tool.HumanApprovalMcpTools;

import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

/** Physically separates agent tool discovery from the human-only apply server. */
@Configuration(proxyBeanMethods = false)
public class OperatorMcpConfiguration {

    public static final String OPERATOR_ENDPOINT = "/operator/mcp";

    @Bean("webMvcStreamableServerTransportProvider")
    @Primary
    WebMvcStreamableServerTransportProvider agentMcpTransport(
            @Qualifier("mcpServerObjectMapper") ObjectMapper objectMapper,
            McpServerStreamableHttpProperties properties) {
        return transport(objectMapper, properties.getMcpEndpoint(), properties);
    }

    @Bean("webMvcStreamableServerRouterFunction")
    RouterFunction<ServerResponse> agentMcpRouter(
            @Qualifier("webMvcStreamableServerTransportProvider")
            WebMvcStreamableServerTransportProvider transport) {
        return transport.getRouterFunction();
    }

    @Bean("operatorMcpTransport")
    @ConditionalOnProperty(prefix = "maluca.mcp", name = "apply-enabled", havingValue = "true")
    WebMvcStreamableServerTransportProvider operatorMcpTransport(
            @Qualifier("mcpServerObjectMapper") ObjectMapper objectMapper,
            McpServerStreamableHttpProperties properties) {
        return transport(objectMapper, OPERATOR_ENDPOINT, properties);
    }

    @Bean("operatorMcpRouter")
    @ConditionalOnProperty(prefix = "maluca.mcp", name = "apply-enabled", havingValue = "true")
    RouterFunction<ServerResponse> operatorMcpRouter(
            @Qualifier("operatorMcpTransport") WebMvcStreamableServerTransportProvider transport) {
        return transport.getRouterFunction();
    }

    @Bean(name = "operatorMcpServer", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "maluca.mcp", name = "apply-enabled", havingValue = "true")
    McpSyncServer operatorMcpServer(
            @Qualifier("operatorMcpTransport") WebMvcStreamableServerTransportProvider transport,
            HumanApprovalMcpTools tools,
            McpServerProperties properties) {
        var callbacks = MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();
        var specifications = McpToolUtils.toSyncToolSpecifications(callbacks);
        return McpServer.sync(transport)
                .serverInfo(properties.getName() + "-operator", properties.getVersion())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(specifications)
                .instructions("Human-only exact-proposal approval and policy application.")
                .requestTimeout(properties.getRequestTimeout())
                .immediateExecution(true)
                .build();
    }

    private static WebMvcStreamableServerTransportProvider transport(
            ObjectMapper objectMapper, String endpoint,
            McpServerStreamableHttpProperties properties) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .mcpEndpoint(endpoint)
                .keepAliveInterval(properties.getKeepAliveInterval())
                .disallowDelete(properties.isDisallowDelete())
                .build();
    }
}
