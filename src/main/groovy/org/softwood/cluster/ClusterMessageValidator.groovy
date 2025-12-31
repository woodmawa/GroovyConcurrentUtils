package org.softwood.cluster

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * Validates and signs cluster messages using HMAC-based message authentication.
 * 
 * <p>Provides cryptographic verification that cluster messages have not been
 * tampered with during transmission. Uses HMAC (Hash-based Message Authentication Code)
 * to generate and verify message signatures.</p>
 * 
 * <h3>Usage Example:</h3>
 * <pre>
 * def validator = new ClusterMessageValidator('my-secret-key', 'HmacSHA256')
 * 
 * // Sign a message
 * def message = "task-123:run-456:COMPLETED"
 * def signature = validator.sign(message)
 * 
 * // Verify a message
 * if (validator.verify(message, signature)) {
 *     println "Message is valid and untampered"
 * } else {
 *     println "Message has been tampered with!"
 * }
 * </pre>
 * 
 * <h3>Security Considerations:</h3>
 * <ul>
 *   <li>Use a strong, random signing key (minimum 32 characters)</li>
 *   <li>Keep the signing key secret and never log it</li>
 *   <li>Use HmacSHA256 or stronger algorithms</li>
 *   <li>Verification uses constant-time comparison to prevent timing attacks</li>
 * </ul>
 * 
 * @since 2.0.0
 */
@Slf4j
@CompileStatic
class ClusterMessageValidator {
    
    private final String signingKey
    private final String algorithm
    private final Mac hmac
    
    /**
     * Creates a message validator with specified signing key and algorithm.
     * 
     * @param signingKey Secret key for HMAC signing (minimum 32 characters recommended)
     * @param algorithm HMAC algorithm (e.g., 'HmacSHA256', 'HmacSHA512')
     * @throws IllegalArgumentException if signing key is too short or algorithm is invalid
     */
    ClusterMessageValidator(String signingKey, String algorithm = 'HmacSHA256') {
        if (!signingKey || signingKey.length() < 32) {
            throw new IllegalArgumentException(
                "Signing key must be at least 32 characters for security. " +
                "Current length: ${signingKey?.length() ?: 0}"
            )
        }
        
        this.signingKey = signingKey
        this.algorithm = algorithm
        
        try {
            this.hmac = Mac.getInstance(algorithm)
            def keySpec = new SecretKeySpec(signingKey.bytes, algorithm)
            this.hmac.init(keySpec)
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Failed to initialize HMAC with algorithm '${algorithm}': ${e.message}", 
                e
            )
        }
        
        log.debug("ClusterMessageValidator initialized with algorithm: ${algorithm}")
    }
    
    /**
     * Signs a message using HMAC.
     * 
     * <p>Generates a Base64-encoded HMAC signature for the message.
     * The signature can be verified later to ensure message integrity.</p>
     * 
     * @param message The message to sign
     * @return Base64-encoded HMAC signature
     */
    synchronized String sign(String message) {
        if (!message) {
            log.warn("Attempted to sign null or empty message")
            return null
        }
        
        try {
            hmac.reset()
            byte[] signature = hmac.doFinal(message.bytes)
            def encoded = Base64.encoder.encodeToString(signature)
            
            log.trace("Signed message (first 20 chars): ${message.take(20)}...")
            return encoded
            
        } catch (Exception e) {
            log.error("Failed to sign message: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Verifies a message signature.
     * 
     * <p>Uses constant-time comparison to prevent timing attacks.
     * Returns true only if the signature exactly matches the expected HMAC.</p>
     * 
     * @param message The original message
     * @param signature The Base64-encoded signature to verify
     * @return true if signature is valid, false otherwise
     */
    boolean verify(String message, String signature) {
        if (!message || !signature) {
            log.warn("Cannot verify: message or signature is null")
            logSecurityEvent("Message validation failed: null message or signature")
            return false
        }
        
        try {
            def expectedSignature = sign(message)
            if (!expectedSignature) {
                log.warn("Failed to generate expected signature for verification")
                logSecurityEvent("Message validation failed: signature generation error")
                return false
            }
            
            // Constant-time comparison to prevent timing attacks
            boolean valid = constantTimeEquals(signature, expectedSignature)
            
            if (!valid) {
                log.warn("Message signature verification failed")
                logSecurityEvent("Message validation failed: signature mismatch for message: ${sanitizeMessage(message)}")
            } else {
                log.trace("Message signature verified successfully")
            }
            
            return valid
            
        } catch (Exception e) {
            log.error("Error during signature verification: ${e.message}", e)
            logSecurityEvent("Message validation error: ${e.message}")
            return false
        }
    }
    
    /**
     * Constant-time string comparison to prevent timing attacks.
     * 
     * <p>Compares two strings in constant time regardless of where they differ.
     * This prevents attackers from using timing information to guess signatures.</p>
     * 
     * @param a First string
     * @param b Second string
     * @return true if strings are equal, false otherwise
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return a == b
        }
        
        if (a.length() != b.length()) {
            return false
        }
        
        byte[] aBytes = a.bytes
        byte[] bBytes = b.bytes
        
        int result = 0
        for (int i = 0; i < aBytes.length; i++) {
            result |= aBytes[i] ^ bBytes[i]
        }
        
        return result == 0
    }
    
    /**
     * Sanitizes a message for logging by keeping only first/last few characters.
     * Prevents sensitive data from appearing in logs.
     * 
     * @param message The message to sanitize
     * @return Sanitized message safe for logging
     */
    private static String sanitizeMessage(String message) {
        if (!message) return "null"
        if (message.length() <= 20) return message
        
        return "${message.take(10)}...${message.takeRight(10)}"
    }
    
    /**
     * Logs a security event.
     * In production, this should integrate with your security monitoring system.
     * 
     * @param event The security event to log
     */
    private void logSecurityEvent(String event) {
        log.warn("SECURITY EVENT: ${event}")
        // In production, send to SIEM or security monitoring system
    }
    
    /**
     * Gets the algorithm used for signing.
     * 
     * @return HMAC algorithm name
     */
    String getAlgorithm() {
        return algorithm
    }
}
