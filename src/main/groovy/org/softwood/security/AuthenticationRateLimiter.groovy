package org.softwood.security

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Rate limiter for authentication attempts to prevent brute force attacks.
 * 
 * <p>Tracks failed login attempts per username and IP address, applying
 * temporary lockouts when thresholds are exceeded.</p>
 * 
 * <h3>Features:</h3>
 * <ul>
 *   <li>Configurable max attempts before lockout</li>
 *   <li>Configurable lockout duration</li>
 *   <li>Automatic cleanup of old attempts</li>
 *   <li>Thread-safe for concurrent access</li>
 *   <li>Tracks both username and IP-based attempts</li>
 * </ul>
 * 
 * <h3>Usage:</h3>
 * <pre>
 * def rateLimiter = new AuthenticationRateLimiter(
 *     maxAttempts: 5,
 *     lockoutDurationMinutes: 15
 * )
 * 
 * // Before attempting authentication
 * if (!rateLimiter.isAllowed("user123", "192.168.1.100")) {
 *     throw new SecurityException("Account temporarily locked")
 * }
 * 
 * // After failed authentication
 * rateLimiter.recordFailedAttempt("user123", "192.168.1.100")
 * 
 * // After successful authentication
 * rateLimiter.recordSuccessfulAttempt("user123", "192.168.1.100")
 * </pre>
 * 
 * @since 2.1.0
 */
@Slf4j
@CompileStatic
class AuthenticationRateLimiter {
    
    // Configuration
    private int maxAttempts = 5
    private long lockoutDurationMs = 15 * 60 * 1000  // 15 minutes default
    private long cleanupIntervalMs = 60 * 60 * 1000  // 1 hour
    
    // State tracking
    private final Map<String, LoginAttempts> usernameAttempts = new ConcurrentHashMap<>()
    private final Map<String, LoginAttempts> ipAttempts = new ConcurrentHashMap<>()
    private Instant lastCleanup = Instant.now()
    
    /**
     * Tracks login attempts for a specific identifier (username or IP).
     */
    private static class LoginAttempts {
        int failedCount = 0
        Instant lastAttempt = Instant.now()
        Instant lockedUntil = null
        
        boolean isLocked() {
            if (lockedUntil == null) return false
            if (Instant.now().isAfter(lockedUntil)) {
                // Lock expired
                lockedUntil = null
                failedCount = 0
                return false
            }
            return true
        }
        
        void recordFailure(int maxAttempts, long lockoutDurationMs) {
            failedCount++
            lastAttempt = Instant.now()
            
            if (failedCount >= maxAttempts) {
                lockedUntil = Instant.now().plusMillis(lockoutDurationMs)
            }
        }
        
        void recordSuccess() {
            failedCount = 0
            lastAttempt = Instant.now()
            lockedUntil = null
        }
        
        boolean isStale(long staleThresholdMs) {
            return Instant.now().toEpochMilli() - lastAttempt.toEpochMilli() > staleThresholdMs
        }
    }
    
    /**
     * Sets maximum failed attempts before lockout.
     * @param max maximum attempts (must be > 0)
     */
    void setMaxAttempts(int max) {
        if (max <= 0) {
            throw new IllegalArgumentException("maxAttempts must be > 0")
        }
        this.maxAttempts = max
    }
    
    /**
     * Sets lockout duration in minutes.
     * @param minutes lockout duration (must be > 0)
     */
    void setLockoutDurationMinutes(int minutes) {
        if (minutes <= 0) {
            throw new IllegalArgumentException("lockoutDurationMinutes must be > 0")
        }
        this.lockoutDurationMs = minutes * 60 * 1000L
    }
    
    /**
     * Sets lockout duration in milliseconds.
     * @param ms lockout duration (must be > 0)
     */
    void setLockoutDurationMs(long ms) {
        if (ms <= 0) {
            throw new IllegalArgumentException("lockoutDurationMs must be > 0")
        }
        this.lockoutDurationMs = ms
    }
    
    /**
     * Checks if authentication is allowed for the given username and IP.
     * 
     * @param username the username attempting to authenticate
     * @param ipAddress the source IP address
     * @return true if authentication attempt is allowed, false if locked out
     */
    boolean isAllowed(String username, String ipAddress) {
        if (!username || !ipAddress) {
            log.warn("SECURITY: Rate limiter called with null username or IP")
            return false
        }
        
        // Periodic cleanup
        maybeCleanup()
        
        // Check both username and IP-based locks
        def userLock = usernameAttempts.get(username)
        def ipLock = ipAttempts.get(ipAddress)
        
        boolean userLocked = userLock?.isLocked() ?: false
        boolean ipLocked = ipLock?.isLocked() ?: false
        
        if (userLocked) {
            log.warn("SECURITY: Authentication blocked for username '{}' - account locked until {}", 
                sanitizeForLogging(username), userLock.lockedUntil)
            return false
        }
        
        if (ipLocked) {
            log.warn("SECURITY: Authentication blocked from IP '{}' - IP locked until {}", 
                sanitizeIp(ipAddress), ipLock.lockedUntil)
            return false
        }
        
        return true
    }
    
    /**
     * Records a failed authentication attempt.
     * 
     * @param username the username that failed authentication
     * @param ipAddress the source IP address
     */
    void recordFailedAttempt(String username, String ipAddress) {
        if (!username || !ipAddress) {
            return
        }
        
        // Track by username
        def userAttempts = usernameAttempts.computeIfAbsent(username, { k -> new LoginAttempts() })
        userAttempts.recordFailure(maxAttempts, lockoutDurationMs)
        
        // Track by IP
        def ipAttempts = this.ipAttempts.computeIfAbsent(ipAddress, { k -> new LoginAttempts() })
        ipAttempts.recordFailure(maxAttempts, lockoutDurationMs)
        
        log.warn("SECURITY: Failed authentication attempt for username '{}' from IP '{}' (attempt {}/{})", 
            sanitizeForLogging(username), sanitizeIp(ipAddress), userAttempts.failedCount, maxAttempts)
        
        // Log if locked
        if (userAttempts.isLocked()) {
            log.error("SECURITY: Username '{}' locked until {} after {} failed attempts", 
                sanitizeForLogging(username), userAttempts.lockedUntil, maxAttempts)
        }
        if (ipAttempts.isLocked()) {
            log.error("SECURITY: IP '{}' locked until {} after {} failed attempts", 
                sanitizeIp(ipAddress), ipAttempts.lockedUntil, maxAttempts)
        }
    }
    
    /**
     * Records a successful authentication attempt.
     * Clears any failed attempt tracking for the username and IP.
     * 
     * @param username the username that successfully authenticated
     * @param ipAddress the source IP address
     */
    void recordSuccessfulAttempt(String username, String ipAddress) {
        if (!username || !ipAddress) {
            return
        }
        
        // Clear username tracking
        def userAttempts = usernameAttempts.get(username)
        if (userAttempts) {
            userAttempts.recordSuccess()
            log.debug("Cleared failed attempts for username '{}'", sanitizeForLogging(username))
        }
        
        // Clear IP tracking
        def ipAttempts = this.ipAttempts.get(ipAddress)
        if (ipAttempts) {
            ipAttempts.recordSuccess()
            log.debug("Cleared failed attempts for IP '{}'", sanitizeIp(ipAddress))
        }
    }
    
    /**
     * Manually unlocks a username.
     * Useful for administrative actions.
     * 
     * @param username the username to unlock
     */
    void unlock(String username) {
        def attempts = usernameAttempts.get(username)
        if (attempts) {
            attempts.recordSuccess()
            log.info("SECURITY: Manually unlocked username '{}'", sanitizeForLogging(username))
        }
    }
    
    /**
     * Manually unlocks an IP address.
     * Useful for administrative actions.
     * 
     * @param ipAddress the IP address to unlock
     */
    void unlockIp(String ipAddress) {
        def attempts = ipAttempts.get(ipAddress)
        if (attempts) {
            attempts.recordSuccess()
            log.info("SECURITY: Manually unlocked IP '{}'", sanitizeIp(ipAddress))
        }
    }
    
    /**
     * Gets the number of failed attempts for a username.
     * 
     * @param username the username to check
     * @return number of failed attempts, or 0 if no attempts recorded
     */
    int getFailedAttempts(String username) {
        return usernameAttempts.get(username)?.failedCount ?: 0
    }
    
    /**
     * Gets the time when a username will be unlocked.
     * 
     * @param username the username to check
     * @return unlock time, or null if not locked
     */
    Instant getLockedUntil(String username) {
        def attempts = usernameAttempts.get(username)
        return attempts?.isLocked() ? attempts.lockedUntil : null
    }
    
    /**
     * Clears all rate limiting state.
     * Useful for testing or administrative reset.
     */
    void reset() {
        usernameAttempts.clear()
        ipAttempts.clear()
        log.info("SECURITY: Rate limiter state cleared")
    }
    
    /**
     * Gets statistics about current rate limiting state.
     * 
     * @return map with statistics
     */
    Map<String, Object> getStatistics() {
        int lockedUsers = usernameAttempts.values().count { it.isLocked() } as int
        int lockedIps = ipAttempts.values().count { it.isLocked() } as int
        
        return [
            trackedUsers: usernameAttempts.size(),
            trackedIps: ipAttempts.size(),
            lockedUsers: lockedUsers,
            lockedIps: lockedIps,
            maxAttempts: maxAttempts,
            lockoutDurationMs: lockoutDurationMs
        ] as Map<String, Object>
    }
    
    /**
     * Periodically cleans up old tracking data.
     */
    private void maybeCleanup() {
        long now = Instant.now().toEpochMilli()
        long timeSinceCleanup = now - lastCleanup.toEpochMilli()
        
        if (timeSinceCleanup > cleanupIntervalMs) {
            cleanup()
        }
    }
    
    /**
     * Removes stale tracking entries (older than 24 hours).
     */
    private void cleanup() {
        long staleThreshold = 24 * 60 * 60 * 1000  // 24 hours
        
        int removedUsers = usernameAttempts.entrySet().removeIf { entry ->
            entry.value.isStale(staleThreshold)
        } ? 1 : 0
        
        int removedIps = ipAttempts.entrySet().removeIf { entry ->
            entry.value.isStale(staleThreshold)
        } ? 1 : 0
        
        lastCleanup = Instant.now()
        
        if (removedUsers > 0 || removedIps > 0) {
            log.debug("Rate limiter cleanup: removed {} stale user entries, {} stale IP entries", 
                removedUsers, removedIps)
        }
    }
    
    /**
     * Sanitizes username for safe logging.
     */
    private static String sanitizeForLogging(String username) {
        if (!username || username.length() < 3) {
            return "<redacted>"
        }
        // Show first 3 chars only
        return username.substring(0, 3) + "***"
    }
    
    /**
     * Sanitizes IP address for safe logging.
     */
    private static String sanitizeIp(String ip) {
        if (!ip) {
            return "<unknown>"
        }
        // For IPv4, show first two octets
        def parts = ip.split('\\.')
        if (parts.length == 4) {
            return "${parts[0]}.${parts[1]}.*.*"
        }
        // For IPv6 or other, show prefix
        if (ip.length() > 8) {
            return ip.substring(0, 8) + "***"
        }
        return "***"
    }
}
