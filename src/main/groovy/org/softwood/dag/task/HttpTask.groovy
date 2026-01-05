package org.softwood.dag.task

import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import org.softwood.promise.Promise
import org.softwood.dag.task.cookies.CookieJar

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration

/**
 * HttpTask - Execute HTTP requests with full support for all methods, headers, auth, and body types.
 * 
 * <h3>Features:</h3>
 * <ul>
 *   <li>All HTTP methods: GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS</li>
 *   <li>Request bodies: JSON, form data, multipart, raw</li>
 *   <li>Authentication: Bearer token, Basic auth, API key</li>
 *   <li>Headers, query parameters, cookies</li>
 *   <li>Response parsing and validation</li>
 *   <li>Automatic retry on 5xx errors</li>
 *   <li>Timeout support</li>
 * </ul>
 * 
 * <h3>Usage Examples:</h3>
 * <pre>
 * // Simple GET
 * httpTask("fetch-user") {
 *     url "https://api.example.com/users/123"
 * }
 * 
 * // POST with JSON
 * httpTask("create-user") {
 *     url "https://api.example.com/users"
 *     method POST
 *     json {
 *         name "Alice"
 *         email "alice@example.com"
 *     }
 *     auth {
 *         bearer "token-123"
 *     }
 * }
 * 
 * // Form data
 * httpTask("login") {
 *     url "https://api.example.com/auth/login"
 *     method POST
 *     formData {
 *         username "alice"
 *         password "secret"
 *     }
 * }
 * </pre>
 */
@Slf4j
class HttpTask extends TaskBase<HttpResponse> {
    
    private final HttpRequest request = new HttpRequest()
    private final HttpClient httpClient
    private CookieJar cookieJar
    
    // Deferred configurations (with resolver)
    private Closure deferredAuthConfig
    private Closure deferredHeadersConfig
    private Closure deferredJsonConfig
    private Closure deferredFormDataConfig
    private Closure deferredQueryParamsConfig
    private Object urlValue  // Can be String or Closure
    
    HttpTask(String id, String name, TaskContext ctx) {
        super(id, name, ctx)
        
        // Create HTTP client with default configuration
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }
    
    // =========================================================================
    // DSL Methods - Request Configuration
    // =========================================================================
    
    /**
     * Set the request URL.
     * 
     * <h3>Static URL:</h3>
     * <pre>
     * url "https://api.example.com/users"
     * </pre>
     * 
     * <h3>Dynamic URL (with resolver):</h3>
     * <pre>
     * url { r -> "${r.global('api.baseUrl')}/users" }
     * url { r -> "https://api.example.com/users/${r.prev.userId}" }
     * </pre>
     */
    void url(Object value) {
        this.urlValue = value
    }
    
    /**
     * Set the HTTP method.
     */
    void method(HttpMethod method) {
        request.method = method
    }
    
    /**
     * Configure request headers.
     * 
     * <h3>Static headers:</h3>
     * <pre>
     * headers {
     *     "Accept" "application/json"
     *     "User-Agent" "MyApp/1.0"
     * }
     * </pre>
     * 
     * <h3>Dynamic headers (with resolver):</h3>
     * <pre>
     * headers { r ->
     *     "X-Request-ID" UUID.randomUUID().toString()
     *     "X-User-ID" r.prev.userId
     *     "Authorization" "Bearer ${r.credential('api.token')}"
     * }
     * </pre>
     */
    void headers(@DelegatesTo(HeadersDsl) Closure config) {
        def dsl = new HeadersDsl(request.headers)
        if (!configureDsl(dsl, config, null, null)) {
            this.deferredHeadersConfig = config
        }
    }
    
    /**
     * Set a single header.
     */
    void header(String name, String value) {
        request.headers[name] = value
    }
    
    /**
     * Configure query parameters.
     * 
     * <h3>Static params:</h3>
     * <pre>
     * queryParams {
     *     "page" "1"
     *     "size" "20"
     * }
     * </pre>
     * 
     * <h3>Dynamic params (with resolver):</h3>
     * <pre>
     * queryParams { r ->
     *     "userId" r.prev.userId
     *     "filter" r.global('default.filter')
     * }
     * </pre>
     */
    void queryParams(@DelegatesTo(QueryParamsDsl) Closure config) {
        def dsl = new QueryParamsDsl(request.queryParams)
        if (!configureDsl(dsl, config, null, null)) {
            this.deferredQueryParamsConfig = config
        }
    }
    
    /**
     * Set a single query parameter.
     */
    void queryParam(String name, String value) {
        request.queryParams[name] = value
    }
    
    /**
     * Set request body (String, Map, List, or byte[]).
     */
    void body(Object body) {
        request.body = body
    }
    
    /**
     * Set request body as JSON from a map/closure.
     * 
     * <h3>Static JSON:</h3>
     * <pre>
     * json {
     *     name "Alice"
     *     email "alice@example.com"
     * }
     * </pre>
     * 
     * <h3>Dynamic JSON (with resolver):</h3>
     * <pre>
     * json { r ->
     *     userId r.prev.userId
     *     timestamp new Date().time
     *     apiKey r.credential('api.key')
     * }
     * </pre>
     */
    void json(@DelegatesTo(JsonBodyDsl) Closure config) {
        def dsl = new JsonBodyDsl()
        if (!configureDsl(dsl, config, null, null)) {
            this.deferredJsonConfig = config
            request.contentType = 'application/json'
        } else {
            request.body = dsl.data
            request.contentType = 'application/json'
        }
    }
    
    /**
     * Set Content-Type header.
     */
    void contentType(String contentType) {
        request.contentType = contentType
    }
    
    /**
     * Configure form data (application/x-www-form-urlencoded).
     * 
     * <h3>Static form data:</h3>
     * <pre>
     * formData {
     *     username "alice"
     *     password "secret"
     * }
     * </pre>
     * 
     * <h3>Dynamic form data (with resolver):</h3>
     * <pre>
     * formData { r ->
     *     username r.credential('app.username')
     *     password r.credential('app.password')
     *     client_id r.global('oauth.clientId')
     * }
     * </pre>
     */
    void formData(@DelegatesTo(FormDataDsl) Closure config) {
        def dsl = new FormDataDsl(request.formData)
        if (!configureDsl(dsl, config, null, null)) {
            this.deferredFormDataConfig = config
        }
    }
    
    /**
     * Configure multipart form data for file uploads.
     * 
     * Usage:
     *   multipart {
     *       field "userId", "123"
     *       field "description", "My file"
     *       file "avatar", "profile.jpg", imageBytes, "image/jpeg"
     *   }
     */
    void multipart(@DelegatesTo(MultipartDsl) Closure config) {
        def dsl = new MultipartDsl(request.multipartParts)
        config.delegate = dsl
        config.resolveStrategy = Closure.DELEGATE_FIRST
        config.call()
    }
    
    /**
     * Enable cookie jar for this request.
     * Cookies will be persisted across requests in the same graph.
     * 
     * Usage:
     *   cookieJar true
     */
    void cookieJar(boolean enable) {
        request.useCookieJar = enable
        
        if (enable) {
            // Get or create shared cookie jar from context globals
            if (!ctx.globals.has('_httpCookieJar')) {
                ctx.globals.set('_httpCookieJar', new CookieJar())
            }
            this.cookieJar = ctx.globals.get('_httpCookieJar') as CookieJar
        }
    }
    
    /**
     * Use a specific cookie jar instance.
     */
    void cookieJar(CookieJar jar) {
        request.useCookieJar = true
        this.cookieJar = jar
    }
    
    /**
     * Configure authentication.
     * 
     * <h3>Static auth:</h3>
     * <pre>
     * auth {
     *     bearer "token-123"
     *     // or: basic "user", "pass"
     *     // or: apiKey "key-123"
     * }
     * </pre>
     * 
     * <h3>Dynamic auth (with resolver - RECOMMENDED for security):</h3>
     * <pre>
     * auth { r ->
     *     bearer r.credential('api.token')
     *     // or: basic r.credential('api.username'), r.credential('api.password')
     *     // or: apiKey r.credential('api.key')
     * }
     * </pre>
     */
    void auth(@DelegatesTo(AuthDsl) Closure config) {
        def dsl = new AuthDsl(request)
        if (!configureDsl(dsl, config, null, null)) {
            this.deferredAuthConfig = config
        }
    }
    
    /**
     * Set request timeout.
     */
    void timeout(Duration timeout) {
        request.timeout = timeout
    }
    
    /**
     * Validate response status code.
     * 
     * Usage:
     *   validateStatus { status -> status < 400 }
     */
    void validateStatus(Closure<Boolean> validator) {
        request.statusValidator = validator
    }
    
    // =========================================================================
    // HTTP-Specific Retry Logic
    // =========================================================================
    
    @Override
    protected boolean isRetriable(Throwable err) {
        // Don't retry configuration errors
        if (err instanceof IllegalStateException) return false
        if (err instanceof IllegalArgumentException) return false
        
        // Don't retry client errors (4xx) - these are permanent failures
        if (err instanceof HttpClientErrorException) return false
        
        // Retry server errors (5xx) - these are often transient
        if (err instanceof HttpServerErrorException) return true
        
        // Retry network errors
        return super.isRetriable(err)
    }
    
    // =========================================================================
    // Task Execution
    // =========================================================================
    
    @Override
    protected Promise<HttpResponse> runTask(TaskContext ctx, Object prevValue) {
        log.debug("HttpTask($id): executing HTTP request")
        
        // Evaluate URL (supports both static and dynamic)
        String finalUrl = evaluateValue(urlValue, ctx, prevValue)
        
        if (!finalUrl) {
            throw new IllegalStateException("HttpTask($id): URL not specified")
        }
        
        request.url = finalUrl
        
        // Execute deferred configurations
        if (deferredAuthConfig) {
            def dsl = new AuthDsl(request)
            executeDeferredConfig(deferredAuthConfig, dsl, ctx, prevValue)
        }
        
        if (deferredHeadersConfig) {
            def dsl = new HeadersDsl(request.headers)
            executeDeferredConfig(deferredHeadersConfig, dsl, ctx, prevValue)
        }
        
        if (deferredQueryParamsConfig) {
            def dsl = new QueryParamsDsl(request.queryParams)
            executeDeferredConfig(deferredQueryParamsConfig, dsl, ctx, prevValue)
        }
        
        if (deferredJsonConfig) {
            def dsl = new JsonBodyDsl()
            executeDeferredConfig(deferredJsonConfig, dsl, ctx, prevValue)
            request.body = dsl.data
        }
        
        if (deferredFormDataConfig) {
            def dsl = new FormDataDsl(request.formData)
            executeDeferredConfig(deferredFormDataConfig, dsl, ctx, prevValue)
        }
        
        return ctx.promiseFactory.executeAsync {
            executeHttpRequest()
        }
    }
    
    /**
     * Execute the HTTP request using Java HttpClient.
     */
    private HttpResponse executeHttpRequest() {
        try {
            // Build the request
            def builder = java.net.http.HttpRequest.newBuilder()
                .uri(new URI(request.buildUrl()))
                .timeout(request.timeout)
            
            // Add headers
            request.headers.each { name, value ->
                builder.header(name, value)
            }
            
            // Add auth headers
            if (request.bearerToken) {
                builder.header("Authorization", "Bearer ${request.bearerToken}")
            } else if (request.basicAuthUser) {
                def credentials = "${request.basicAuthUser}:${request.basicAuthPassword}"
                def encoded = Base64.encoder.encodeToString(credentials.getBytes('UTF-8'))
                builder.header("Authorization", "Basic ${encoded}")
            } else if (request.apiKey) {
                builder.header(request.apiKeyHeader, request.apiKey)
            }
            
            // Add cookies from cookie jar
            if (cookieJar && request.useCookieJar) {
                def uri = new URI(request.buildUrl())
                def cookieHeader = cookieJar.getCookieHeader(
                    uri.host,
                    uri.path ?: "/",
                    uri.scheme == "https"
                )
                if (cookieHeader) {
                    builder.header("Cookie", cookieHeader)
                    log.debug("HttpTask($id): added cookies: ${cookieHeader}")
                }
            }
            
            // Handle multipart Content-Type with boundary BEFORE building the body
            if (request.isMultipart()) {
                def boundary = "----WebKitFormBoundary${UUID.randomUUID().toString().replace('-', '')}"
                request.multipartBoundary = boundary
                builder.header("Content-Type", "multipart/form-data; boundary=${boundary}")
            }
            
            // Set Content-Type if not already set
            else if (request.hasBody() && !request.headers.containsKey("Content-Type")) {
                builder.header("Content-Type", request.getEffectiveContentType())
            }
            
            // Set request body based on method
            def javaRequest
            switch (request.method) {
                case HttpMethod.GET:
                    javaRequest = builder.GET().build()
                    break
                case HttpMethod.POST:
                    javaRequest = builder.POST(buildBodyPublisher()).build()
                    break
                case HttpMethod.PUT:
                    javaRequest = builder.PUT(buildBodyPublisher()).build()
                    break
                case HttpMethod.DELETE:
                    javaRequest = builder.DELETE().build()
                    break
                case HttpMethod.PATCH:
                    javaRequest = builder.method("PATCH", buildBodyPublisher()).build()
                    break
                case HttpMethod.HEAD:
                    javaRequest = builder.method("HEAD", BodyPublishers.noBody()).build()
                    break
                case HttpMethod.OPTIONS:
                    javaRequest = builder.method("OPTIONS", BodyPublishers.noBody()).build()
                    break
                default:
                    throw new IllegalStateException("Unsupported HTTP method: ${request.method}")
            }
            
            // Execute the request
            log.debug("HttpTask($id): sending ${request.method} to ${request.url}")
            def javaResponse = httpClient.send(javaRequest, BodyHandlers.ofByteArray())
            
            // Build response
            def response = new HttpResponse(
                javaResponse.statusCode(),
                "", // Java HttpClient doesn't provide status message
                javaResponse.headers().map(),
                javaResponse.body()
            )
            
            log.debug("HttpTask($id): received response status ${response.statusCode}")
            
            // Store cookies from response
            if (cookieJar && request.useCookieJar) {
                def setCookieHeaders = javaResponse.headers().allValues("Set-Cookie")
                if (setCookieHeaders) {
                    def uri = new URI(request.buildUrl())
                    cookieJar.addFromSetCookieHeaders(setCookieHeaders, uri.host)
                    log.debug("HttpTask($id): stored ${setCookieHeaders.size()} cookies")
                }
            }
            
            // Validate status if validator provided
            if (request.statusValidator) {
                if (!request.statusValidator.call(response.statusCode)) {
                    throw new HttpStatusException(
                        "HTTP request failed with status ${response.statusCode}",
                        response
                    )
                }
            } else {
                // Default validation: fail on 4xx and 5xx
                if (response.isClientError()) {
                    throw new HttpClientErrorException("HTTP ${response.statusCode}: ${response.body}", response)
                }
                if (response.isServerError()) {
                    throw new HttpServerErrorException("HTTP ${response.statusCode}: ${response.body}", response)
                }
            }
            
            return response
            
        } catch (IOException e) {
            log.error("HttpTask($id): network error: ${e.message}", e)
            throw new RuntimeException("HTTP request failed: ${e.message}", e)
        } catch (InterruptedException e) {
            log.error("HttpTask($id): interrupted: ${e.message}", e)
            Thread.currentThread().interrupt()
            throw new RuntimeException("HTTP request interrupted", e)
        }
    }
    
    /**
     * Build the request body publisher.
     */
    private java.net.http.HttpRequest.BodyPublisher buildBodyPublisher() {
        if (request.hasFormData()) {
            return BodyPublishers.ofByteArray(request.getFormEncodedBody())
        }
        
        if (request.isMultipart()) {
            return buildMultipartBody()
        }
        
        if (request.body != null) {
            return BodyPublishers.ofByteArray(request.getBodyBytes())
        }
        
        return BodyPublishers.noBody()
    }
    
    /**
     * Build multipart body with proper boundary and formatting.
     * 
     * Implements RFC 7578 multipart/form-data format:
     * --boundary\r\n
     * Content-Disposition: form-data; name="field"\r\n
     * \r\n
     * value\r\n
     * --boundary\r\n
     * Content-Disposition: form-data; name="file"; filename="image.jpg"\r\n
     * Content-Type: image/jpeg\r\n
     * \r\n
     * <binary data>\r\n
     * --boundary--\r\n
     */
    private java.net.http.HttpRequest.BodyPublisher buildMultipartBody() {
        if (request.multipartParts.isEmpty()) {
            return BodyPublishers.noBody()
        }
        
        // Use the boundary that was already set in executeHttpRequest
        def boundary = request.multipartBoundary
        if (!boundary) {
            throw new IllegalStateException("Multipart boundary not set")
        }
        
        // Build multipart body
        def output = new ByteArrayOutputStream()
        
        request.multipartParts.each { part ->
            // Write boundary
            output.write("--${boundary}\r\n".getBytes('UTF-8'))
            
            // Write Content-Disposition header
            if (part.filename) {
                output.write("Content-Disposition: form-data; name=\"${part.name}\"; filename=\"${part.filename}\"\r\n".getBytes('UTF-8'))
            } else {
                output.write("Content-Disposition: form-data; name=\"${part.name}\"\r\n".getBytes('UTF-8'))
            }
            
            // Write Content-Type header
            output.write("Content-Type: ${part.contentType}\r\n".getBytes('UTF-8'))
            
            // Empty line before content
            output.write("\r\n".getBytes('UTF-8'))
            
            // Write content
            output.write(part.content)
            
            // End of part
            output.write("\r\n".getBytes('UTF-8'))
        }
        
        // Write final boundary
        output.write("--${boundary}--\r\n".getBytes('UTF-8'))
        
        return BodyPublishers.ofByteArray(output.toByteArray())
    }
    
    // =========================================================================
    // Inner DSL Classes
    // =========================================================================
    
    class HeadersDsl {
        private final Map<String, String> headers
        
        HeadersDsl(Map<String, String> headers) {
            this.headers = headers
        }
        
        def propertyMissing(String name, value) {
            headers[name] = value.toString()
        }
        
        def methodMissing(String name, args) {
            if (args.length == 1) {
                headers[name] = args[0].toString()
            }
        }
    }
    
    class QueryParamsDsl {
        private final Map<String, String> params
        
        QueryParamsDsl(Map<String, String> params) {
            this.params = params
        }
        
        def propertyMissing(String name, value) {
            params[name] = value.toString()
        }
        
        def methodMissing(String name, args) {
            if (args.length == 1) {
                params[name] = args[0].toString()
            }
        }
    }
    
    class JsonBodyDsl {
        final Map<String, Object> data = [:]
        
        def propertyMissing(String name, value) {
            data[name] = value
        }
        
        def methodMissing(String name, args) {
            if (args.length == 1) {
                data[name] = args[0]
            }
        }
    }
    
    class FormDataDsl {
        private final Map<String, String> formData
        
        FormDataDsl(Map<String, String> formData) {
            this.formData = formData
        }
        
        def propertyMissing(String name, value) {
            formData[name] = value.toString()
        }
        
        def methodMissing(String name, args) {
            if (args.length == 1) {
                formData[name] = args[0].toString()
            }
        }
    }
    
    class AuthDsl {
        private final HttpRequest request
        
        AuthDsl(HttpRequest request) {
            this.request = request
        }
        
        void bearer(String token) {
            request.bearerToken = token
        }
        
        void basic(String username, String password) {
            request.basicAuthUser = username
            request.basicAuthPassword = password
        }
        
        void apiKey(String key, String headerName = "X-API-Key") {
            request.apiKey = key
            request.apiKeyHeader = headerName
        }
    }
    
    class MultipartDsl {
        private final List<MultipartPart> parts
        
        MultipartDsl(List<MultipartPart> parts) {
            this.parts = parts
        }
        
        /**
         * Add a text field to the multipart request.
         * 
         * Usage:
         *   field "userId", "123"
         */
        void field(String name, String value) {
            parts.add(MultipartPart.field(name, value))
        }
        
        /**
         * Add a file to the multipart request.
         * 
         * Usage:
         *   file "avatar", "profile.jpg", imageBytes, "image/jpeg"
         */
        void file(String name, String filename, byte[] content, String contentType = "application/octet-stream") {
            parts.add(MultipartPart.file(name, filename, content, contentType))
        }
    }
}

/**
 * Base exception for HTTP errors.
 */
class HttpStatusException extends RuntimeException {
    final HttpResponse response
    
    HttpStatusException(String message, HttpResponse response) {
        super(message)
        this.response = response
    }
}

/**
 * Exception for HTTP client errors (4xx).
 */
class HttpClientErrorException extends HttpStatusException {
    HttpClientErrorException(String message, HttpResponse response) {
        super(message, response)
    }
}

/**
 * Exception for HTTP server errors (5xx).
 */
class HttpServerErrorException extends HttpStatusException {
    HttpServerErrorException(String message, HttpResponse response) {
        super(message, response)
    }
}
