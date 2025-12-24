package org.softwood.dag.task

import groovy.json.JsonOutput
import java.time.Duration

/**
 * HTTP request configuration.
 * 
 * Holds all request parameters including URL, method, headers, body, auth, etc.
 */
class HttpRequest {
    
    String url
    HttpMethod method = HttpMethod.GET
    Map<String, String> headers = [:]
    Map<String, String> queryParams = [:]
    
    // Request body
    Object body  // Can be String, Map, byte[], etc.
    String contentType
    
    // Timeout
    Duration timeout = Duration.ofSeconds(30)
    
    // Authentication
    String bearerToken
    String basicAuthUser
    String basicAuthPassword
    String apiKey
    String apiKeyHeader = "X-API-Key"
    
    // Multipart data
    List<MultipartPart> multipartParts = []
    
    // Form data
    Map<String, String> formData = [:]
    
    // Cookies
    boolean useCookies = false
    Map<String, String> cookies = [:]
    
    // Response validation
    Closure<Boolean> statusValidator
    
    /**
     * Build the complete URL with query parameters.
     */
    String buildUrl() {
        if (queryParams.isEmpty()) {
            return url
        }
        
        def query = queryParams.collect { k, v -> 
            "${URLEncoder.encode(k, 'UTF-8')}=${URLEncoder.encode(v, 'UTF-8')}"
        }.join('&')
        
        return url.contains('?') ? "${url}&${query}" : "${url}?${query}"
    }
    
    /**
     * Get the request body as bytes.
     */
    byte[] getBodyBytes() {
        if (body == null) {
            return null
        }
        
        if (body instanceof byte[]) {
            return body as byte[]
        }
        
        if (body instanceof String) {
            return ((String) body).getBytes('UTF-8')
        }
        
        if (body instanceof Map || body instanceof List) {
            // Serialize to JSON
            def json = JsonOutput.toJson(body)
            if (!contentType) {
                contentType = 'application/json'
            }
            return json.getBytes('UTF-8')
        }
        
        // Fallback: toString()
        return body.toString().getBytes('UTF-8')
    }
    
    /**
     * Get form-encoded body.
     */
    byte[] getFormEncodedBody() {
        if (formData.isEmpty()) {
            return null
        }
        
        def encoded = formData.collect { k, v ->
            "${URLEncoder.encode(k, 'UTF-8')}=${URLEncoder.encode(v, 'UTF-8')}"
        }.join('&')
        
        if (!contentType) {
            contentType = 'application/x-www-form-urlencoded'
        }
        
        return encoded.getBytes('UTF-8')
    }
    
    /**
     * Check if this is a multipart request.
     */
    boolean isMultipart() {
        return !multipartParts.isEmpty()
    }
    
    /**
     * Check if this has form data.
     */
    boolean hasFormData() {
        return !formData.isEmpty()
    }
    
    /**
     * Check if this has a request body.
     */
    boolean hasBody() {
        return body != null || hasFormData() || isMultipart()
    }
    
    /**
     * Get effective Content-Type header.
     */
    String getEffectiveContentType() {
        if (contentType) {
            return contentType
        }
        
        if (isMultipart()) {
            return 'multipart/form-data'
        }
        
        if (hasFormData()) {
            return 'application/x-www-form-urlencoded'
        }
        
        if (body instanceof Map || body instanceof List) {
            return 'application/json'
        }
        
        return 'text/plain'
    }
    
    @Override
    String toString() {
        return "HttpRequest[${method} ${url}]"
    }
}

/**
 * Represents a single part in a multipart request.
 */
class MultipartPart {
    final String name
    final String filename
    final byte[] content
    final String contentType
    
    MultipartPart(String name, String filename, byte[] content, String contentType = "application/octet-stream") {
        this.name = name
        this.filename = filename
        this.content = content
        this.contentType = contentType
    }
    
    /**
     * Create a text field part.
     */
    static MultipartPart field(String name, String value) {
        return new MultipartPart(name, null, value.getBytes('UTF-8'), 'text/plain')
    }
    
    /**
     * Create a file part.
     */
    static MultipartPart file(String name, String filename, byte[] content, String contentType = "application/octet-stream") {
        return new MultipartPart(name, filename, content, contentType)
    }
}
