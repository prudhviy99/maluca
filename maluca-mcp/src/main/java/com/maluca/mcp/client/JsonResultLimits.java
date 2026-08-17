package com.maluca.mcp.client;

import com.fasterxml.jackson.databind.JsonNode;

final class JsonResultLimits {

    private JsonResultLimits() {
    }

    static JsonNode requireArray(String operation, JsonNode result, int maximumItems) {
        if (!result.isArray()) {
            throw new UpstreamServiceException(operation, 0,
                    operation + " returned an invalid result shape");
        }
        if (result.size() > maximumItems) {
            throw new UpstreamServiceException(operation, 0,
                    operation + " returned more than " + maximumItems + " items");
        }
        return result;
    }

    static JsonNode requireObject(String operation, JsonNode result) {
        if (!result.isObject()) {
            throw new UpstreamServiceException(operation, 0,
                    operation + " returned an invalid result shape");
        }
        return result;
    }
}
