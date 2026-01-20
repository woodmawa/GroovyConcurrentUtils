package org.softwood.dag.task

import groovy.util.logging.Slf4j

/**
 * Security configuration for HttpTask operations.
 *
 * Controls HTTP request security including URL whitelisting, header validation,
 * and protection against SSRF (Server-Side Request Forgery) attacks.
 *
 * <h3>Usage Example:</h3>
 * <pre>
 * def securityConfig = HttpTaskSecurityConfig.builder()
 *     .urlWhitelistEnabled(true)
 *     .allowedHosts(['api.example.com', 'cdn.example.com'])
 *     .allowedSchemes(['https'])  // Block HTTP
 *     .blockPrivateNetworks(true)
 *     .maxRedirects(5)
 *     .build()
 *
 * httpTask {
 *     name 'secure-api-call'
 *     securityConfig securityConfig
 *     url 'https://api.example.com/data'
 * }
 * </pre>
 *
 * @author Will Woodman
 * @since 2.0
 */
@Slf4j
class HttpTaskSecurityConfig {

    // =========================================================================
    // URL Whitelisting (SSRF Protection)
    // =========================================================================

    /**
     * When true, enforces URL whitelist validation.
     * All URLs must match allowedHosts or allowedUrlPatterns.
     *
     * Default: false (opt-in for backward compatibility)
     */
    boolean urlWhitelistEnabled = false

    /**
     * List of allowed hostnames (exact match or wildcard).
     *
     * Examples:
     * - "api.example.com" (exact match)
     * - "*.example.com" (wildcard match)
     * - "192.168.1.100" (IP address)
     *
     * Default: empty list (requires explicit configuration when urlWhitelistEnabled=true)
     */
    List<String> allowedHosts = []

    /**
     * List of allowed URL patterns (regex).
     *
     * Example:
     * - "https://api\\.example\\.com/v[0-9]+/.*"
     *
     * Default: empty list
     */
    List<String> allowedUrlPatterns = []

    /**
     * List of allowed schemes (http, https, etc.).
     *
     * Default: ['https'] (secure by default)
     */
    List<String> allowedSchemes = ['https']

    /**
     * When true, blocks requests to private/internal networks.
     * Prevents SSRF attacks against internal infrastructure.
     *
     * Blocked ranges:
     * - 10.0.0.0/8
     * - 172.16.0.0/12
     * - 192.168.0.0/16
     * - 127.0.0.0/8 (localhost)
     * - 169.254.0.0/16 (link-local)
     * - ::1 (IPv6 localhost)
     * - fe80::/10 (IPv6 link-local)
     *
     * Default: true (secure by default)
     */
    boolean blockPrivateNetworks = true

    /**
     * When true, blocks requests to cloud metadata endpoints.
     * Prevents SSRF attacks against cloud provider metadata services.
     *
     * Blocked endpoints:
     * - 169.254.169.254 (AWS, Azure, GCP)
     * - 169.254.170.2 (AWS ECS)
     * - metadata.google.internal (GCP)
     *
     * Default: true (secure by default)
     */
    boolean blockCloudMetadata = true

    // =========================================================================
    // HTTP Security
    // =========================================================================

    /**
     * Maximum number of redirects to follow.
     * Set to 0 to disable redirects entirely.
     *
     * Default: 5
     */
    int maxRedirects = 5

    /**
     * When true, validates redirect URLs against whitelist.
     * Prevents redirect-based SSRF attacks.
     *
     * Default: true (secure by default)
     */
    boolean validateRedirects = true

    /**
     * Request timeout in seconds.
     *
     * Default: 30 seconds
     */
    int timeoutSeconds = 30

    /**
     * When true, enforces HTTPS for all requests (blocks HTTP).
     *
     * Default: false (opt-in for flexibility)
     */
    boolean requireHttps = false

    // =========================================================================
    // Logging
    // =========================================================================

    /**
     * When true, logs blocked requests for security monitoring.
     *
     * Default: true
     */
    boolean logBlockedRequests = true

    /**
     * When true, error messages include full URL details.
     * When false, error messages are sanitized to prevent information leakage.
     *
     * Default: false (secure by default)
     */
    boolean verboseErrors = false

    // =========================================================================
    // Validation
    // =========================================================================

    /**
     * Validates configuration for security issues.
     *
     * @throws IllegalStateException if configuration is invalid
     */
    void validate() {
        if (urlWhitelistEnabled && allowedHosts.isEmpty() && allowedUrlPatterns.isEmpty()) {
            throw new IllegalStateException(
                "urlWhitelistEnabled=true requires at least one allowedHost or allowedUrlPattern"
            )
        }

        if (maxRedirects < 0) {
            throw new IllegalArgumentException("maxRedirects must be >= 0")
        }

        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("timeoutSeconds must be >= 1")
        }

        if (allowedSchemes.isEmpty()) {
            throw new IllegalStateException("allowedSchemes cannot be empty")
        }
    }

    /**
     * Validates a URL against the security policy.
     *
     * @param url URL to validate
     * @return true if URL is allowed, false if blocked
     * @throws SecurityException if URL violates security policy
     */
    boolean validateUrl(String url) {
        if (!url) {
            throw new IllegalArgumentException("URL cannot be null or empty")
        }

        try {
            URI uri = new URI(url)

            // Check scheme
            if (!allowedSchemes.contains(uri.scheme?.toLowerCase())) {
                if (logBlockedRequests) {
                    log.warn("Blocked HTTP request: disallowed scheme '${uri.scheme}' (allowed: ${allowedSchemes})")
                }
                throw new SecurityException("URL scheme '${uri.scheme}' not allowed. Allowed schemes: ${allowedSchemes}")
            }

            // Check HTTPS requirement
            if (requireHttps && uri.scheme?.toLowerCase() != 'https') {
                if (logBlockedRequests) {
                    log.warn("Blocked HTTP request: requireHttps=true but scheme is '${uri.scheme}'")
                }
                throw new SecurityException("HTTPS required but URL uses '${uri.scheme}'")
            }

            // Check URL whitelist (if enabled)
            if (urlWhitelistEnabled) {
                boolean allowed = false

                // Check allowed hosts
                for (String allowedHost : allowedHosts) {
                    if (matchesHost(uri.host, allowedHost)) {
                        allowed = true
                        break
                    }
                }

                // Check allowed URL patterns
                if (!allowed) {
                    for (String pattern : allowedUrlPatterns) {
                        if (url.matches(pattern)) {
                            allowed = true
                            break
                        }
                    }
                }

                if (!allowed) {
                    if (logBlockedRequests) {
                        log.warn("Blocked HTTP request: URL not in whitelist: ${verboseErrors ? url : '<redacted>'}")
                    }
                    throw new SecurityException("URL not in whitelist: ${verboseErrors ? url : '<host blocked>'}")
                }
            }

            // Check private networks
            if (blockPrivateNetworks && isPrivateNetwork(uri.host)) {
                if (logBlockedRequests) {
                    log.warn("Blocked HTTP request: private network access attempt: ${verboseErrors ? uri.host : '<redacted>'}")
                }
                throw new SecurityException("Access to private networks is blocked")
            }

            // Check cloud metadata endpoints
            if (blockCloudMetadata && isCloudMetadataEndpoint(uri.host)) {
                if (logBlockedRequests) {
                    log.warn("Blocked HTTP request: cloud metadata endpoint access attempt: ${verboseErrors ? uri.host : '<redacted>'}")
                }
                throw new SecurityException("Access to cloud metadata endpoints is blocked")
            }

            return true

        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL: ${verboseErrors ? url : '<invalid>'}", e)
        }
    }

    /**
     * Checks if a host matches an allowed host pattern.
     * Supports wildcards (*.example.com).
     */
    private boolean matchesHost(String host, String allowedHost) {
        if (!host || !allowedHost) {
            return false
        }

        host = host.toLowerCase()
        allowedHost = allowedHost.toLowerCase()

        // Exact match
        if (host == allowedHost) {
            return true
        }

        // Wildcard match (*.example.com)
        if (allowedHost.startsWith('*.')) {
            String domain = allowedHost.substring(2)
            return host.endsWith(domain)
        }

        return false
    }

    /**
     * Checks if a host is a private network address.
     */
    private boolean isPrivateNetwork(String host) {
        if (!host) {
            return false
        }

        // Check for localhost
        if (host.toLowerCase() in ['localhost', '127.0.0.1', '::1']) {
            return true
        }

        // Check for private IP ranges
        try {
            InetAddress addr = InetAddress.getByName(host)
            return addr.isSiteLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()
        } catch (Exception e) {
            // Not an IP address, check hostname patterns
            return host.endsWith('.local') || host.endsWith('.internal')
        }
    }

    /**
     * Checks if a host is a cloud metadata endpoint.
     */
    private boolean isCloudMetadataEndpoint(String host) {
        if (!host) {
            return false
        }

        host = host.toLowerCase()

        // AWS/Azure/GCP metadata
        if (host == '169.254.169.254' || host == '169.254.170.2') {
            return true
        }

        // GCP metadata
        if (host == 'metadata.google.internal' || host.endsWith('.metadata.google.internal')) {
            return true
        }

        return false
    }

    // =========================================================================
    // Builder Pattern
    // =========================================================================

    static Builder builder() {
        new Builder()
    }

    static class Builder {
        private HttpTaskSecurityConfig instance = new HttpTaskSecurityConfig()

        Builder urlWhitelistEnabled(boolean val) { instance.urlWhitelistEnabled = val; this }
        Builder allowedHosts(List<String> val) { instance.allowedHosts = val; this }
        Builder allowedUrlPatterns(List<String> val) { instance.allowedUrlPatterns = val; this }
        Builder allowedSchemes(List<String> val) { instance.allowedSchemes = val; this }
        Builder blockPrivateNetworks(boolean val) { instance.blockPrivateNetworks = val; this }
        Builder blockCloudMetadata(boolean val) { instance.blockCloudMetadata = val; this }
        Builder maxRedirects(int val) { instance.maxRedirects = val; this }
        Builder validateRedirects(boolean val) { instance.validateRedirects = val; this }
        Builder timeoutSeconds(int val) { instance.timeoutSeconds = val; this }
        Builder requireHttps(boolean val) { instance.requireHttps = val; this }
        Builder logBlockedRequests(boolean val) { instance.logBlockedRequests = val; this }
        Builder verboseErrors(boolean val) { instance.verboseErrors = val; this }

        HttpTaskSecurityConfig build() {
            instance.validate()
            instance
        }
    }

    // =========================================================================
    // Factory Methods
    // =========================================================================

    /**
     * Creates a permissive configuration suitable for development/testing.
     * NOT RECOMMENDED FOR PRODUCTION.
     */
    static HttpTaskSecurityConfig permissive() {
        // SECURITY: Prevent permissive configuration in production
        def env = System.getenv('ENVIRONMENT') ?: System.getProperty('environment') ?: ''
        if (env.toLowerCase() in ['production', 'prod', 'live', 'staging']) {
            throw new IllegalStateException(
                "Permissive HttpTask configuration cannot be used in production environment. " +
                "ENVIRONMENT=${env}. " +
                "Use strict() factory method instead."
            )
        }

        log.warn("=" * 80)
        log.warn("⚠️  USING PERMISSIVE HTTP TASK CONFIGURATION")
        log.warn("  - URL whitelist: DISABLED")
        log.warn("  - Private network blocking: DISABLED")
        log.warn("  - Cloud metadata blocking: DISABLED")
        log.warn("  - FOR DEVELOPMENT/TESTING ONLY")
        log.warn("=" * 80)

        return builder()
            .urlWhitelistEnabled(false)
            .blockPrivateNetworks(false)
            .blockCloudMetadata(false)
            .allowedSchemes(['http', 'https'])
            .maxRedirects(10)
            .verboseErrors(true)
            .logBlockedRequests(false)
            .build()
    }

    /**
     * Creates a strict configuration suitable for production.
     * Requires explicit allowedHosts.
     */
    static HttpTaskSecurityConfig strict(List<String> allowedHosts) {
        return builder()
            .urlWhitelistEnabled(true)
            .allowedHosts(allowedHosts)
            .allowedSchemes(['https'])
            .blockPrivateNetworks(true)
            .blockCloudMetadata(true)
            .maxRedirects(5)
            .validateRedirects(true)
            .requireHttps(true)
            .timeoutSeconds(30)
            .verboseErrors(false)
            .logBlockedRequests(true)
            .build()
    }
}
