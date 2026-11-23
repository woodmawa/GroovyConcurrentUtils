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
            checkRequest(new URI(url), [])
            return true
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL: ${verboseErrors ? url : '<invalid>'}", e)
        }
    }

    /**
     * GCU 1.3.0 (D3): the check HttpTask makes before every request and before every redirect hop.
     *
     * <ol>
     *   <li>scheme, HTTPS requirement and the host allow-list, as configured;</li>
     *   <li>every address the host resolves to: a cloud metadata address is refused; a loopback
     *       address is refused unless {@code allowLocal} names that host and port; any other private,
     *       link-local, unique-local, carrier-NAT or multicast address is refused.</li>
     * </ol>
     *
     * A host that does not resolve passes: the request then fails on its own, with nothing contacted.
     *
     * The addresses checked here are the ones the JVM has just resolved and cached, and the
     * connection that follows uses that cache (30 seconds by default), so a name that answers
     * differently a moment later does not get a second answer in. That holds only while the JVM's
     * address cache is on: do not run with {@code networkaddress.cache.ttl=0}.
     *
     * @param uri        the request or redirect target
     * @param allowLocal entries of the form {@code host:port} naming local services the flow may call
     * @throws SecurityException if the target is refused
     */
    void checkRequest(URI uri, Collection<String> allowLocal) {
        String scheme = uri.scheme?.toLowerCase()
        if (!allowedSchemes.contains(scheme)) {
            refuse("URL scheme '${uri.scheme}' not allowed. Allowed schemes: ${allowedSchemes}", "disallowed scheme '${uri.scheme}'")
        }
        if (requireHttps && scheme != 'https') {
            refuse("HTTPS required but URL uses '${uri.scheme}'", "requireHttps=true but scheme is '${uri.scheme}'")
        }

        String host = uri.host
        if (!host) {
            refuse("URL has no host", "URL has no host")
        }
        if (host.startsWith('[') && host.endsWith(']')) {
            host = host.substring(1, host.length() - 1)
        }

        if (urlWhitelistEnabled) {
            boolean allowed = allowedHosts.any { String pattern -> matchesHost(host, pattern) } ||
                    allowedUrlPatterns.any { String pattern -> uri.toString().matches(pattern) }
            if (!allowed) {
                refuse("URL not in whitelist: ${verboseErrors ? uri : '<host blocked>'}", "URL not in whitelist: ${verboseErrors ? uri : '<redacted>'}")
            }
        }

        if (blockCloudMetadata && isCloudMetadataEndpoint(host)) {
            refuse("Access to cloud metadata endpoints is blocked", "cloud metadata endpoint: ${verboseErrors ? host : '<redacted>'}")
        }
        if (!blockPrivateNetworks && !blockCloudMetadata) {
            return
        }

        InetAddress[] addresses
        try {
            addresses = InetAddress.getAllByName(host)
        } catch (UnknownHostException ignored) {
            if (blockPrivateNetworks && (host.toLowerCase().endsWith('.local') || host.toLowerCase().endsWith('.internal'))) {
                refuse("Access to private networks is blocked", "private host name: ${verboseErrors ? host : '<redacted>'}")
            }
            return
        }

        int port = uri.port > 0 ? uri.port : (scheme == 'https' ? 443 : 80)
        for (InetAddress address : addresses) {
            if (blockCloudMetadata && isCloudMetadataEndpoint(address.hostAddress)) {
                refuse("Access to cloud metadata endpoints is blocked", "cloud metadata address behind ${verboseErrors ? host : '<redacted>'}")
            }
            if (!blockPrivateNetworks) {
                continue
            }
            if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
                if (!localAllowed(host, port, allowLocal)) {
                    refuse("Access to a local address is blocked. A flow that calls a local service names it: allowLocal '${host}:${port}'",
                            "local address not named by the flow: ${verboseErrors ? host + ':' + port : '<redacted>'}")
                }
                continue
            }
            if (isPrivateAddress(address)) {
                refuse("Access to private networks is blocked", "private network address: ${verboseErrors ? host : '<redacted>'}")
            }
        }
    }

    private void refuse(String message, String logDetail) {
        if (logBlockedRequests) {
            log.warn("Blocked HTTP request: ${logDetail}")
        }
        throw new SecurityException(message)
    }

    /**
     * GCU 1.3.0 (D3): checks and normalises an {@code allowLocal} entry. It must name a host and a port.
     *
     * @return the entry in lower case
     * @throws IllegalArgumentException if it is not {@code host:port}
     */
    static String localEntry(Object entry) {
        String text = entry?.toString()?.trim()?.toLowerCase()
        int colon = text ? text.lastIndexOf(':') : -1
        boolean ok = colon > 0 && colon < text.length() - 1 && text.substring(colon + 1).isInteger()
        if (!ok) {
            throw new IllegalArgumentException(
                    "allowLocal takes 'host:port', for example allowLocal 'localhost:8082'; got '" + entry + "'")
        }
        return text
    }

    /** True if an allowLocal entry names this local target: same port, and the same host or any loopback name. */
    private static boolean localAllowed(String host, int port, Collection<String> allowLocal) {
        if (!allowLocal) {
            return false
        }
        for (String entry : allowLocal) {
            int colon = entry.lastIndexOf(':')
            if (colon <= 0) continue
            String entryHost = entry.substring(0, colon)
            if (entryHost.startsWith('[') && entryHost.endsWith(']')) {
                entryHost = entryHost.substring(1, entryHost.length() - 1)
            }
            if (!entry.substring(colon + 1).isInteger() || entry.substring(colon + 1).toInteger() != port) continue
            if (entryHost.equalsIgnoreCase(host) || isLoopbackName(entryHost)) {
                return true
            }
        }
        return false
    }

    private static boolean isLoopbackName(String host) {
        if (host.equalsIgnoreCase('localhost')) return true
        // address literals only: a name here is never looked up
        if (!(host ==~ /[0-9.]+/) && !host.contains(':')) return false
        try {
            return InetAddress.getByName(host).isLoopbackAddress()
        } catch (Exception ignored) {
            return false
        }
    }

    /** Site-local, link-local, IPv6 unique-local (fc00::/7), carrier-grade NAT (100.64.0.0/10) and multicast. */
    private static boolean isPrivateAddress(InetAddress address) {
        if (address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return true
        }
        byte[] raw = address.address
        if (raw.length == 16) {
            return (raw[0] & 0xfe) == 0xfc
        }
        if (raw.length == 4) {
            return (raw[0] & 0xff) == 100 && (raw[1] & 0xc0) == 64
        }
        return false
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

        // Wildcard match (*.example.com): subdomains only.
        // GCU 1.3.0: this compared with endsWith(domain), so '*.example.com' also let 'evilexample.com' in.
        if (allowedHost.startsWith('*.')) {
            String domain = allowedHost.substring(1)   // keeps the dot: '.example.com'
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

        // AWS/Azure/GCP metadata, AWS ECS, AWS IPv6 metadata
        if (host == '169.254.169.254' || host == '169.254.170.2' || host == 'fd00:ec2:0:0:0:0:0:254' || host == 'fd00:ec2::254') {
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
    /**
     * GCU 1.3.0 (D3): what an HttpTask with no security configuration of its own runs under.
     * HTTP and HTTPS to public hosts; private networks, local addresses and cloud metadata blocked;
     * five redirects, each checked.
     */
    static HttpTaskSecurityConfig standard() {
        return builder()
            .allowedSchemes(['http', 'https'])
            .blockPrivateNetworks(true)
            .blockCloudMetadata(true)
            .maxRedirects(5)
            .build()
    }

    static HttpTaskSecurityConfig permissive() {
        // SECURITY: Prevent permissive configuration in production
        // GCU 1.3.0: no ENVIRONMENT check, in line with FileTaskSecurityConfig.permissive() (D9).
        // An HttpTask with no configuration is guarded; this has to be named to be used.

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