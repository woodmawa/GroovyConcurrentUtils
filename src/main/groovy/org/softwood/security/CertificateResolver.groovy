package org.softwood.security

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.time.temporal.ChronoUnit
import java.time.Instant

/**
 * Resolves certificate paths using multiple strategies.
 * 
 * <p>Checks locations in priority order:</p>
 * <ol>
 *   <li>Explicit path (if provided)</li>
 *   <li>System property</li>
 *   <li>Environment variable</li>
 *   <li>Config file property</li>
 *   <li>Classpath resource</li>
 *   <li>Default test certificates (dev only)</li>
 * </ol>
 * 
 * <h2>Usage Examples</h2>
 * <pre>
 * // Resolve with explicit path
 * def resolver = new CertificateResolver()
 * def path = resolver.resolve(
 *     '/etc/certs/keystore.jks',
 *     'actor.tls.keystore.path',
 *     'ACTOR_TLS_KEYSTORE_PATH',
 *     '/certs/keystore.jks'
 * )
 * 
 * // With config
 * def config = new ConfigSlurper().parse(configFile)
 * def resolver = new CertificateResolver(config, false)
 * </pre>
 * 
 * @since 2.1.0
 */
@Slf4j
@CompileStatic
class CertificateResolver {
    
    private final ConfigObject config
    private final boolean developmentMode
    
    /**
     * Creates a certificate resolver.
     * 
     * @param config optional configuration object
     * @param developmentMode if true, allows fallback to test certificates
     */
    CertificateResolver(ConfigObject config = null, boolean developmentMode = false) {
        this.config = config ?: new ConfigObject()
        this.developmentMode = developmentMode
    }
    
    /**
     * Resolves a certificate path using the resolution strategy.
     * 
     * @param explicitPath user-provided path (highest priority)
     * @param propertyKey configuration property name
     * @param envVarKey environment variable name
     * @param classpathResource resource path in classpath
     * @return resolved path or null if not found
     */
    String resolve(String explicitPath, String propertyKey, 
                   String envVarKey, String classpathResource) {
        
        // 1. Explicit path provided by user
        if (explicitPath && !explicitPath.trim().isEmpty()) {
            def path = resolvePath(explicitPath)
            if (path) {
                log.debug("Certificate resolved via explicit path: ${path}")
                return path
            } else {
                log.warn("Explicit path provided but not found: ${explicitPath}")
            }
        }
        
        // 2. System property (e.g., -Dactor.keystore.path=...)
        if (propertyKey && !propertyKey.trim().isEmpty()) {
            try {
                def systemProp = System.getProperty(propertyKey)
                if (systemProp && !systemProp.trim().isEmpty()) {
                    def path = resolvePath(systemProp)
                    if (path) {
                        log.info("Certificate resolved via system property ${propertyKey}: ${path}")
                        return path
                    }
                }
            } catch (IllegalArgumentException e) {
                log.trace("Invalid property key: ${propertyKey}", e)
            }
        }
        
        // 3. Environment variable
        if (envVarKey && !envVarKey.trim().isEmpty()) {
            try {
                def envVar = System.getenv(envVarKey)
                if (envVar && !envVar.trim().isEmpty()) {
                    def path = resolvePath(envVar)
                    if (path) {
                        log.info("Certificate resolved via environment variable ${envVarKey}: ${path}")
                        return path
                    }
                }
            } catch (Exception e) {
                log.trace("Error reading environment variable ${envVarKey}", e)
            }
        }
        
        // 4. Config file property
        if (config && propertyKey && !propertyKey.trim().isEmpty()) {
            try {
                // Handle both flat and nested properties
                def configPath = getNestedConfigValue(config, propertyKey)
                if (configPath && !configPath.trim().isEmpty()) {
                    def path = resolvePath(configPath)
                    if (path) {
                        log.info("Certificate resolved via config property ${propertyKey}: ${path}")
                        return path
                    }
                }
            } catch (Exception e) {
                log.trace("Error reading config property ${propertyKey}", e)
            }
        }
        
        // 5. Classpath resource (packaged with user's application)
        if (classpathResource && !classpathResource.trim().isEmpty()) {
            def resource = findClasspathResource(classpathResource)
            if (resource) {
                log.info("Certificate resolved via classpath: ${classpathResource}")
                return resource
            }
        }
        
        // 6. Development mode: use bundled test certificates
        if (developmentMode && classpathResource && !classpathResource.trim().isEmpty()) {
            def devResource = findDevelopmentCertificate(classpathResource)
            if (devResource) {
                log.warn("⚠️  Using bundled test certificate for development: ${classpathResource}")
                log.warn("⚠️  DO NOT USE IN PRODUCTION - Configure proper certificates!")
                return devResource
            }
        }
        
        log.error("Failed to resolve certificate. Tried: " +
                 "explicitPath=${explicitPath}, " +
                 "propertyKey=${propertyKey}, " +
                 "envVarKey=${envVarKey}, " +
                 "classpathResource=${classpathResource}")
        return null
    }
    
    /**
     * Validates that a path does not contain directory traversal sequences.
     * Protects against attacks like ../../../../etc/passwd
     * 
     * @param path the path to validate
     * @return true if path is safe, false if it contains traversal attempts
     */
    private boolean isPathSafe(String path) {
        if (!path) return true
        
        // Normalize for checking
        def normalized = path.trim()
        
        // Check for parent directory references
        if (normalized.contains('..')) {
            log.warn("SECURITY: Rejected path containing '..' traversal: ${sanitizePathForLogging(normalized)}")
            return false
        }
        
        // Check for home directory expansion
        if (normalized.startsWith('~')) {
            log.warn("SECURITY: Rejected path starting with '~': ${sanitizePathForLogging(normalized)}")
            return false
        }
        
        // Check for null bytes (can cause path truncation on some systems)
        if (normalized.contains('\u0000')) {
            log.warn("SECURITY: Rejected path containing null byte")
            return false
        }
        
        // Check for double slashes that might be used to bypass filters
        if (normalized.contains('//') || normalized.contains('\\\\')) {
            log.trace("Path contains double slashes: ${sanitizePathForLogging(normalized)}")
            // Don't reject - might be legitimate URL or Windows UNC path
        }
        
        return true
    }
    
    /**
     * Sanitizes a path for logging to avoid exposing sensitive information.
     */
    private String sanitizePathForLogging(String path) {
        if (!path) return "<empty>"
        if (path.length() <= 40) return path
        return path.substring(0, 20) + "..." + path.substring(path.length() - 17)
    }
    
    /**
     * Resolves a path, checking if it exists on filesystem or classpath.
     * 
     * @param path the path to resolve
     * @return resolved path or null
     */
    private String resolvePath(String path) {
        if (!path || path.trim().isEmpty()) return null
        
        def trimmedPath = path.trim()
        
        // SECURITY: Validate path before attempting to resolve
        if (!isPathSafe(trimmedPath)) {
            log.error("SECURITY: Path traversal attempt blocked: ${sanitizePathForLogging(trimmedPath)}")
            return null
        }
        
        // Try as filesystem path
        try {
            def filePath = Paths.get(trimmedPath).normalize()
            
            // Additional check: ensure normalization didn't introduce traversal
            def normalizedStr = filePath.toString()
            if (normalizedStr.contains('..')) {
                log.warn("SECURITY: Normalized path still contains '..': ${sanitizePathForLogging(normalizedStr)}")
                return null
            }
            
            if (Files.exists(filePath)) {
                return filePath.toAbsolutePath().toString()
            }
        } catch (Exception e) {
            log.trace("Path not found on filesystem: ${sanitizePathForLogging(trimmedPath)}", e)
        }
        
        // Try as classpath resource
        def resource = findClasspathResource(trimmedPath)
        if (resource) {
            return resource
        }
        
        return null
    }
    
    /**
     * Retrieves a value from ConfigObject using dot-notation path.
     * Handles both flat keys (e.g., 'my.key') and nested properties (e.g., 'actor.tls.keystore.path').
     * 
     * @param config the ConfigObject to search
     * @param propertyKey the property key (may be dot-separated for nested properties)
     * @return the value as String, or null if not found
     */
    private String getNestedConfigValue(ConfigObject config, String propertyKey) {
        if (!config || !propertyKey) return null
        
        // First try as flat key
        if (config.containsKey(propertyKey)) {
            return config.get(propertyKey) as String
        }
        
        // Try as nested path (e.g., 'actor.tls.keystore.path')
        def parts = propertyKey.split('\\.')
        def current = config
        
        for (int i = 0; i < parts.length - 1; i++) {
            def part = parts[i]
            if (current.containsKey(part)) {
                def next = current.get(part)
                if (next instanceof ConfigObject) {
                    current = next as ConfigObject
                } else {
                    // Not a nested config, can't continue
                    return null
                }
            } else {
                // Path doesn't exist
                return null
            }
        }
        
        // Get final value
        def lastKey = parts[-1]
        if (current.containsKey(lastKey)) {
            return current.get(lastKey) as String
        }
        
        return null
    }
    
    /**
     * Finds a resource on the classpath.
     * 
     * @param resourcePath the resource path
     * @return resource identifier or null
     */
    private String findClasspathResource(String resourcePath) {
        if (!resourcePath || resourcePath.trim().isEmpty()) return null
        
        def trimmedPath = resourcePath.trim()
        
        // Try original path first
        def resource = this.class.getResource(trimmedPath)
        if (resource) {
            return trimmedPath
        }
        
        // If no leading slash, try adding it
        if (!trimmedPath.startsWith('/')) {
            def withSlash = '/' + trimmedPath
            resource = this.class.getResource(withSlash)
            if (resource) {
                return withSlash
            }
        }
        
        // If has leading slash(es), normalize to single slash
        if (trimmedPath.startsWith('/')) {
            def normalized = trimmedPath.replaceAll('^/+', '/')
            if (normalized != trimmedPath) {
                resource = this.class.getResource(normalized)
                if (resource) {
                    return normalized
                }
            }
        }
        
        return null
    }
    
    /**
     * Finds development certificate bundled in test resources.
     * 
     * @param resourcePath the base resource name
     * @return resource identifier or null
     */
    private String findDevelopmentCertificate(String resourcePath) {
        if (!resourcePath || resourcePath.trim().isEmpty()) return null
        
        // Check if we can find it in the test-certs directory
        def trimmedPath = resourcePath.trim().replaceFirst('^/+', '')
        def testCertsPath = '/test-certs/' + trimmedPath
        return findClasspathResource(testCertsPath)
    }
    
    /**
     * Validates that a resolved path can be accessed.
     * 
     * @param resolvedPath the path to validate
     * @return true if accessible
     */
    boolean validatePath(String resolvedPath) {
        if (!resolvedPath) return false
        
        // Check if it's a file
        try {
            def file = new File(resolvedPath)
            if (file.exists() && file.canRead()) {
                return true
            }
        } catch (Exception e) {
            log.trace("Not a readable file: ${resolvedPath}", e)
        }
        
        // Check if it's a classpath resource
        try {
            def stream = this.class.getResourceAsStream(resolvedPath)
            if (stream) {
                stream.close()
                return true
            }
        } catch (Exception e) {
            log.trace("Not a readable classpath resource: ${resolvedPath}", e)
        }
        
        return false
    }
    
    /**
     * Opens an input stream for a resolved path.
     * Supports both filesystem and classpath resources.
     * 
     * @param resolvedPath the path resolved by resolve()
     * @return input stream or null
     */
    InputStream openStream(String resolvedPath) {
        if (!resolvedPath || resolvedPath.trim().isEmpty()) return null
        
        def trimmedPath = resolvedPath.trim()
        
        // Try as file
        try {
            def file = new File(trimmedPath)
            if (file.exists()) {
                return new FileInputStream(file)
            }
        } catch (Exception e) {
            log.trace("Could not open as file: ${trimmedPath}", e)
        }
        
        // Try as classpath resource
        try {
            def stream = this.class.getResourceAsStream(trimmedPath)
            if (stream) {
                return stream
            }
        } catch (Exception e) {
            log.trace("Could not open as classpath resource: ${trimmedPath}", e)
        }
        
        return null
    }
    
    /**
     * Validates that a keystore/truststore contains only valid (non-expired) certificates.
     * 
     * @param resolvedPath path to the keystore/truststore file
     * @param password keystore password
     * @param keystoreType type of keystore (JKS, PKCS12, etc.)
     * @return true if all certificates are valid, false otherwise
     */
    boolean validateCertificateExpiration(String resolvedPath, char[] password, String keystoreType = 'JKS') {
        if (!resolvedPath) {
            log.warn("Cannot validate null certificate path")
            return false
        }
        
        try {
            // Load the keystore
            KeyStore ks = KeyStore.getInstance(keystoreType)
            def stream = openStream(resolvedPath)
            if (!stream) {
                log.error("Cannot open certificate file for validation: ${sanitizePathForLogging(resolvedPath)}")
                return false
            }
            
            try {
                ks.load(stream, password)
            } finally {
                stream.close()
            }
            
            // Check all certificates
            def aliases = ks.aliases()
            boolean allValid = true
            int validCount = 0
            int expiredCount = 0
            int notYetValidCount = 0
            
            while (aliases.hasMoreElements()) {
                def alias = aliases.nextElement()
                def cert = ks.getCertificate(alias)
                
                if (cert instanceof X509Certificate) {
                    X509Certificate x509 = (X509Certificate) cert
                    
                    try {
                        // Check if certificate is currently valid
                        x509.checkValidity()
                        validCount++
                        
                        // Warn if expiring soon (within 30 days)
                        def notAfter = x509.getNotAfter()
                        def daysUntilExpiry = ChronoUnit.DAYS.between(
                            Instant.now(),
                            notAfter.toInstant()
                        )
                        
                        if (daysUntilExpiry < 30) {
                            log.warn("⚠️  Certificate '${alias}' expires in ${daysUntilExpiry} days (on ${notAfter})")
                        }
                        
                        if (daysUntilExpiry < 7) {
                            log.error("🚨 Certificate '${alias}' expires in ${daysUntilExpiry} days! URGENT renewal needed!")
                        }
                        
                    } catch (CertificateExpiredException e) {
                        log.error("❌ Certificate '${alias}' has EXPIRED on ${x509.getNotAfter()}")
                        expiredCount++
                        allValid = false
                        
                    } catch (CertificateNotYetValidException e) {
                        log.error("❌ Certificate '${alias}' is not yet valid until ${x509.getNotBefore()}")
                        notYetValidCount++
                        allValid = false
                    }
                }
            }
            
            if (allValid) {
                log.info("✓ All ${validCount} certificate(s) in keystore are valid")
            } else {
                log.error("Certificate validation failed: ${expiredCount} expired, ${notYetValidCount} not yet valid, ${validCount} valid")
            }
            
            return allValid
            
        } catch (Exception e) {
            log.error("Failed to validate certificates in ${sanitizePathForLogging(resolvedPath)}", e)
            return false
        }
    }
}
