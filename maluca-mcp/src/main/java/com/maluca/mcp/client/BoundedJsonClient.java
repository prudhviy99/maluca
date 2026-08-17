package com.maluca.mcp.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.function.Function;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** A small blocking JSON client that never buffers more than the configured cap. */
public final class BoundedJsonClient {

    private final String service;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final int maxResponseBytes;

    public BoundedJsonClient(
            String service,
            RestClient restClient,
            ObjectMapper objectMapper,
            int maxResponseBytes) {
        this.service = service;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.maxResponseBytes = maxResponseBytes;
    }

    public JsonNode get(Function<UriBuilder, URI> uri) {
        return exchange(restClient.get().uri(uri));
    }

    public JsonNode post(String path, Object body) {
        return post(path, body, null);
    }

    public JsonNode post(String path, Object body, String operation) {
        try {
            return exchange(restClient.post().uri(path).body(body));
        } catch (UpstreamServiceException exception) {
            if (operation != null && (exception.statusCode() == 0
                    || (exception.statusCode() >= 200 && exception.statusCode() < 300)
                    || exception.statusCode() >= 500)) {
                throw new IndeterminateUpstreamOperationException(operation, exception);
            }
            throw exception;
        }
    }

    private JsonNode exchange(RestClient.RequestHeadersSpec<?> request) {
        try {
            return request.exchange((clientRequest, response) -> {
                HttpStatusCode status = response.getStatusCode();
                if (!status.is2xxSuccessful()) {
                    throw new UpstreamServiceException(service, status.value(),
                            service + " returned HTTP " + status.value());
                }
                byte[] bytes = readBounded(response.getBody());
                if (bytes.length == 0) {
                    return JsonNodeFactory.instance.objectNode();
                }
                try {
                    return objectMapper.readTree(bytes);
                } catch (IOException exception) {
                    throw new UpstreamServiceException(service, status.value(),
                            service + " returned invalid JSON");
                }
            });
        } catch (UpstreamServiceException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new UpstreamServiceException(service, service + " request failed", exception);
        }
    }

    private byte[] readBounded(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(maxResponseBytes + 1);
        if (bytes.length > maxResponseBytes) {
            throw new UpstreamServiceException(service, 0,
                    service + " response exceeded " + maxResponseBytes + " bytes");
        }
        return bytes;
    }
}
