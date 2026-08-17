package com.maluca.mcp.client;

public class UpstreamServiceException extends RuntimeException {

    private final String service;
    private final int statusCode;

    public UpstreamServiceException(String service, int statusCode, String message) {
        super(message);
        this.service = service;
        this.statusCode = statusCode;
    }

    public UpstreamServiceException(String service, String message, Throwable cause) {
        super(message, cause);
        this.service = service;
        this.statusCode = 0;
    }

    public String service() {
        return service;
    }

    public int statusCode() {
        return statusCode;
    }
}
