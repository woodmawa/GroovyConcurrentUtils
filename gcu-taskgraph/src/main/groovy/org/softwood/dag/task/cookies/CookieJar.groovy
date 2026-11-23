package org.softwood.dag.task.cookies

import groovy.util.logging.Slf4j

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Cookie storage and management for HTTP requests.
 * 
 * Handles cookie persistence across multiple HTTP requests, including:
 * - Cookie parsing from Set-Cookie headers
 * - Cookie expiry handling
 * - Domain and path matching
 * - Secure and HttpOnly flags
 * 
 * <h3>Usage:</h3>
 * <pre>
 * def jar = new CookieJar()
 * 
 * // Store cookies from response
 * jar.addFromSetCookieHeaders(["sessionId=abc123; Path=/; HttpOnly"])
 * 
 * // Get cookies for request
 * def cookieHeader = jar.getCookieHeader("example.com", "/api")
 * </pre>
 */
@Slf4j
class CookieJar {
    
    private final Map<String, Cookie> cookies = [:].asSynchronized()
    
    /**
     * Add cookies from Set-Cookie response headers.
     * 
     * @param setCookieHeaders List of Set-Cookie header values
     * @param domain The domain the cookies came from
     */
    void addFromSetCookieHeaders(List<String> setCookieHeaders, String domain = null) {
        setCookieHeaders?.each { headerValue ->
            try {
                def cookie = Cookie.parse(headerValue, domain)
                if (cookie) {
                    addCookie(cookie)
                }
            } catch (Exception e) {
                log.warn("Failed to parse Set-Cookie header: ${headerValue}", e)
            }
        }
    }
    
    /**
     * Add a single cookie to the jar.
     */
    void addCookie(Cookie cookie) {
        if (cookie.isExpired()) {
            // Remove expired cookie
            cookies.remove(cookie.key)
            log.debug("Removed expired cookie: ${cookie.name}")
        } else {
            cookies[cookie.key] = cookie
            log.debug("Added cookie: ${cookie.name} for domain ${cookie.domain}")
        }
    }
    
    /**
     * Get cookies matching the given domain and path as a Cookie header string.
     * 
     * @param domain The request domain (e.g., "api.example.com")
     * @param path The request path (e.g., "/api/users")
     * @param secure Whether the request is HTTPS
     * @return Cookie header value (e.g., "sessionId=abc123; userId=456")
     */
    String getCookieHeader(String domain, String path = "/", boolean secure = false) {
        def matchingCookies = getMatchingCookies(domain, path, secure)
        
        if (matchingCookies.isEmpty()) {
            return null
        }
        
        return matchingCookies.collect { it.toCookieHeaderValue() }.join("; ")
    }
    
    /**
     * Get all cookies matching the domain, path, and security requirements.
     */
    List<Cookie> getMatchingCookies(String domain, String path = "/", boolean secure = false) {
        // Remove expired cookies
        removeExpiredCookies()
        
        return cookies.values().findAll { cookie ->
            cookie.matches(domain, path, secure)
        }
    }
    
    /**
     * Remove expired cookies from the jar.
     */
    void removeExpiredCookies() {
        def now = Instant.now()
        def expired = cookies.values().findAll { it.isExpired() }
        expired.each { cookie ->
            cookies.remove(cookie.key)
            log.debug("Removed expired cookie: ${cookie.name}")
        }
    }
    
    /**
     * Clear all cookies.
     */
    void clear() {
        cookies.clear()
        log.debug("Cleared all cookies")
    }
    
    /**
     * Get number of cookies in jar.
     */
    int size() {
        removeExpiredCookies()
        return cookies.size()
    }
    
    /**
     * Check if jar contains a cookie with the given name.
     */
    boolean hasCookie(String name) {
        removeExpiredCookies()
        return cookies.values().any { it.name == name }
    }
    
    /**
     * Get a cookie by name (first match).
     */
    Cookie getCookie(String name) {
        removeExpiredCookies()
        return cookies.values().find { it.name == name }
    }
    
    @Override
    String toString() {
        removeExpiredCookies()
        return "CookieJar[${cookies.size()} cookies]"
    }
}

/**
 * Represents a single HTTP cookie with all its attributes.
 */
@Slf4j
class Cookie {
    
    final String name
    final String value
    String domain
    String path = "/"
    Instant expires
    Long maxAge  // seconds
    boolean secure = false
    boolean httpOnly = false
    String sameSite
    
    Cookie(String name, String value) {
        this.name = name
        this.value = value
    }
    
    /**
     * Parse a Set-Cookie header value into a Cookie object.
     * 
     * Format: name=value; Domain=example.com; Path=/; Expires=...; Max-Age=...; Secure; HttpOnly; SameSite=...
     */
    static Cookie parse(String setCookieHeader, String defaultDomain = null) {
        def parts = setCookieHeader.split(';').collect { it.trim() }
        
        if (parts.isEmpty()) {
            return null
        }
        
        // First part is name=value
        def nameValue = parts[0].split('=', 2)
        if (nameValue.length != 2) {
            log.warn("Invalid cookie format: ${setCookieHeader}")
            return null
        }
        
        def cookie = new Cookie(nameValue[0].trim(), nameValue[1].trim())
        cookie.domain = defaultDomain
        
        // Parse attributes
        parts[1..-1].each { part ->
            def attr = part.split('=', 2)
            def attrName = attr[0].trim().toLowerCase()
            def attrValue = attr.length > 1 ? attr[1].trim() : null
            
            switch (attrName) {
                case 'domain':
                    cookie.domain = attrValue
                    break
                case 'path':
                    cookie.path = attrValue
                    break
                case 'expires':
                    cookie.expires = parseExpires(attrValue)
                    break
                case 'max-age':
                    try {
                        cookie.maxAge = Long.parseLong(attrValue)
                    } catch (NumberFormatException e) {
                        log.warn("Invalid Max-Age value: ${attrValue}")
                    }
                    break
                case 'secure':
                    cookie.secure = true
                    break
                case 'httponly':
                    cookie.httpOnly = true
                    break
                case 'samesite':
                    cookie.sameSite = attrValue
                    break
            }
        }
        
        return cookie
    }
    
    /**
     * Parse Expires date (RFC 1123 format).
     */
    private static Instant parseExpires(String expiresStr) {
        try {
            // Try RFC 1123 format: "Wed, 09 Jun 2021 10:18:14 GMT"
            def formatter = DateTimeFormatter.RFC_1123_DATE_TIME
            return ZonedDateTime.parse(expiresStr, formatter).toInstant()
        } catch (DateTimeParseException e) {
            log.warn("Failed to parse cookie Expires date: ${expiresStr}")
            return null
        }
    }
    
    /**
     * Check if this cookie has expired.
     */
    boolean isExpired() {
        def now = Instant.now()
        
        // Check Max-Age first (takes precedence over Expires)
        if (maxAge != null) {
            // Max-Age of 0 or negative means immediate expiry
            return maxAge <= 0
        }
        
        if (expires != null) {
            return now.isAfter(expires)
        }
        
        // No expiry means session cookie (not expired)
        return false
    }
    
    /**
     * Check if this cookie matches the given domain, path, and security.
     */
    boolean matches(String requestDomain, String requestPath, boolean requestSecure) {
        // Check domain
        if (domain && !domainMatches(requestDomain, domain)) {
            return false
        }
        
        // Check path
        if (path && !pathMatches(requestPath, path)) {
            return false
        }
        
        // Check secure flag
        if (secure && !requestSecure) {
            return false
        }
        
        return true
    }
    
    /**
     * Check if request domain matches cookie domain.
     * Cookie domain ".example.com" matches "api.example.com" and "example.com"
     */
    private static boolean domainMatches(String requestDomain, String cookieDomain) {
        if (!cookieDomain) return true
        
        // Exact match
        if (requestDomain.equalsIgnoreCase(cookieDomain)) {
            return true
        }
        
        // Domain starts with dot - match subdomain
        if (cookieDomain.startsWith('.')) {
            return requestDomain.toLowerCase().endsWith(cookieDomain.toLowerCase())
        }
        
        // Cookie domain without dot - exact match only
        return false
    }
    
    /**
     * Check if request path matches cookie path.
     * Cookie path "/api" matches "/api/users" but not "/admin"
     */
    private static boolean pathMatches(String requestPath, String cookiePath) {
        if (!cookiePath) return true
        
        // Exact match
        if (requestPath == cookiePath) {
            return true
        }
        
        // Request path starts with cookie path
        if (requestPath.startsWith(cookiePath)) {
            // Check for path boundary
            return cookiePath.endsWith('/') || requestPath.charAt(cookiePath.length()) == '/'
        }
        
        return false
    }
    
    /**
     * Convert cookie to "name=value" format for Cookie header.
     */
    String toCookieHeaderValue() {
        return "${name}=${value}"
    }
    
    /**
     * Get unique key for this cookie (domain + path + name).
     */
    String getKey() {
        return "${domain}:${path}:${name}"
    }
    
    @Override
    String toString() {
        return "Cookie[${name}=${value}, domain=${domain}, path=${path}]"
    }
}
