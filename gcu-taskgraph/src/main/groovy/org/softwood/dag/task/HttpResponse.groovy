package org.softwood.dag.task

import groovy.json.JsonSlurper

/**
 * Wrapper for HTTP response data.
 * 
 * Provides convenient access to status, headers, and body content.
 */
class HttpResponse {
    
    final int statusCode
    final String statusMessage
    final Map<String, List<String>> headers
    final String body
    final byte[] bodyBytes
    
    HttpResponse(int statusCode, String statusMessage, Map<String, List<String>> headers, byte[] bodyBytes) {
        this.statusCode = statusCode
        this.statusMessage = statusMessage ?: ""
        this.headers = headers ?: [:]
        this.bodyBytes = bodyBytes ?: new byte[0]
        this.body = bodyBytes ? new String(bodyBytes, "UTF-8") : ""
    }
    
    /**
     * Check if response indicates success (2xx status code).
     */
    boolean isSuccess() {
        statusCode >= 200 && statusCode < 300
    }
    
    /**
     * Check if response indicates client error (4xx status code).
     */
    boolean isClientError() {
        statusCode >= 400 && statusCode < 500
    }
    
    /**
     * Check if response indicates server error (5xx status code).
     */
    boolean isServerError() {
        statusCode >= 500 && statusCode < 600
    }
    
    /**
     * Check if response indicates redirection (3xx status code).
     */
    boolean isRedirect() {
        statusCode >= 300 && statusCode < 400
    }
    
    /**
     * Parse response body as JSON.
     * 
     * @return parsed JSON object (Map or List)
     * @throws groovy.json.JsonException if body is not valid JSON
     */
    def json() {
        if (!body) {
            return null
        }
        return new JsonSlurper().parseText(body)
    }
    
    /**
     * Get the value of a response header (case-insensitive).
     * 
     * @param name header name
     * @return first header value, or null if not present
     */
    String header(String name) {
        def values = headers.find { k, v -> k.equalsIgnoreCase(name) }?.value
        return values ? values.first() : null
    }
    
    /**
     * Get all values of a response header (case-insensitive).
     * 
     * @param name header name
     * @return list of header values, or empty list if not present
     */
    List<String> headers(String name) {
        def values = headers.find { k, v -> k.equalsIgnoreCase(name) }?.value
        return values ?: []
    }
    
    /**
     * Get Content-Type header value.
     */
    String getContentType() {
        return header("Content-Type")
    }
    
    @Override
    String toString() {
        return "HttpResponse[status=$statusCode $statusMessage, body=${body?.take(100)}...]"
    }
}
