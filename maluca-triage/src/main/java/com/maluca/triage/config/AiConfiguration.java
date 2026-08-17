package com.maluca.triage.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;

@Configuration
public class AiConfiguration {

    /**
     * Spring AI does not expose an Ollama-specific socket timeout property. Supplying
     * the API bean here gives both chat and embedding calls an explicit upper bound.
     */
    @Bean
    OllamaApi boundedOllamaApi(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            TriageProperties properties) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.agent().inferenceTimeout());
        requestFactory.setReadTimeout(properties.agent().inferenceTimeout());
        return OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
    }

    @Bean
    ChatClient triageChatClient(ChatModel chatModel) {
        // Qwen 3 emits a separate reasoning stream unless thinking is disabled.
        // Keeping that stream out of the response makes the production path match
        // the frozen evaluation runner and protects strict JSON conversion.
        return ChatClient.builder(chatModel)
                .defaultOptions(productionChatOptions())
                .build();
    }

    static OllamaChatOptions productionChatOptions() {
        return OllamaChatOptions.builder().disableThinking().build();
    }

    @Bean
    McpSyncHttpClientRequestCustomizer mcpAuthentication(TriageProperties properties) {
        String token = properties.security().mcpToken();
        return (request, method, uri, body, context) -> {
            if (token != null && !token.isBlank()) {
                request.header("Authorization", "Bearer " + token);
            }
        };
    }
}
