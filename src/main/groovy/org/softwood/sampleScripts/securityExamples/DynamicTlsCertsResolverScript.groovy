package org.softwood.sampleScripts.securityExamples

import org.softwood.actor.remote.rsocket.RSocketTransport
import org.softwood.security.CertificateResolver

/**
 * Practical Usage Examples for TLS Configuration in GroovyConcurrentUtils
 *
 * This file demonstrates the various ways users can configure TLS/SSL
 * for actor remoting in their projects.
 */

// =============================================================================
// EXAMPLE 1: Development with Bundled Test Certificates
// =============================================================================

/**
 * For development and testing, use bundled test certificates.
 * These are packaged in your test resources.
 */
def developmentExample() {
    def tlsConfig = new RSocketTransport.TlsConfig(
            enabled: true,
            keyStorePath: '/test-certs/server-keystore.jks',
            keyStorePassword: 'changeit',
            trustStorePath: '/test-certs/truststore.jks',
            trustStorePassword: 'changeit',
            developmentMode: true  // Allows fallback to test certs
    )

    def transport = new RSocketTransport(actorSystem, 7000, true, tlsConfig)
    transport.start()
}

// =============================================================================
// EXAMPLE 2: Production with Classpath Resources
// =============================================================================

/**
 * Production deployment with certificates in src/main/resources/certs/
 *
 * Project structure:
 * my-app/
 * ├── src/main/resources/
 * │   └── certs/
 * │       ├── keystore.jks
 * │       └── truststore.jks
 */
def productionClasspathExample() {
    // Passwords should come from environment variables
    def keystorePassword = System.getenv('KEYSTORE_PASSWORD') ?: 'changeit'
    def truststorePassword = System.getenv('TRUSTSTORE_PASSWORD') ?: 'changeit'

    def tlsConfig = new RSocketTransport.TlsConfig(
            enabled: true,
            keyStorePath: '/certs/keystore.jks',      // Classpath resource
            keyStorePassword: keystorePassword,
            trustStorePath: '/certs/truststore.jks',  // Classpath resource
            trustStorePassword: truststorePassword
    )

    def transport = new RSocketTransport(actorSystem, 7000, true, tlsConfig)
    transport.start()
}

// =============================================================================
// EXAMPLE 3: Using System Properties
// =============================================================================

/**
 * Configure via command line arguments:
 *
 * java -Dactor.tls.keystore.path=/etc/myapp/certs/keystore.jks \
 *      -Dactor.tls.keystore.password=secretpass \
 *      -Dactor.tls.truststore.path=/etc/myapp/certs/truststore.jks \
 *      -Dactor.tls.truststore.password=secretpass \
 *      -jar myapp.jar
 */
def systemPropertiesExample() {
    def tlsConfig = new RSocketTransport.TlsConfig(
            enabled: true,
            // These will be resolved from system properties
            keyStorePath: System.getProperty('actor.tls.keystore.path'),
            keyStorePassword: System.getProperty('actor.tls.keystore.password'),
            trustStorePath: System.getProperty('actor.tls.truststore.path'),
            trustStorePassword: System.getProperty('actor.tls.truststore.password')
    )

    def transport = new RSocketTransport(actorSystem, 7000, true, tlsConfig)
    transport.start()
}

// =============================================================================
// EXAMPLE 4: Using Environment Variables
// =============================================================================

/**
 * Configure via environment:
 *
 * export ACTOR_TLS_KEYSTORE_PATH=/etc/myapp/certs/keystore.jks
 * export ACTOR_TLS_KEYSTORE_PASSWORD=secretpass
 * export ACTOR_TLS_TRUSTSTORE_PATH=/etc/myapp/certs/truststore.jks
 * export ACTOR_TLS_TRUSTSTORE_PASSWORD=secretpass
 */
def environmentVariablesExample() {
    def tlsConfig = new RSocketTransport.TlsConfig(
            enabled: true,
            keyStorePath: System.getenv('ACTOR_TLS_KEYSTORE_PATH'),
            keyStorePassword: System.getenv('ACTOR_TLS_KEYSTORE_PASSWORD'),
            trustStorePath: System.getenv('ACTOR_TLS_TRUSTSTORE_PATH'),
            trustStorePassword: System.getenv('ACTOR_TLS_TRUSTSTORE_PASSWORD')
    )

    def transport = new RSocketTransport(actorSystem, 7000, true, tlsConfig)
    transport.start()
}

// =============================================================================
// EXAMPLE 5: Using Configuration File
// =============================================================================

/**
 * Create a config file: tls-config.groovy
 *
 * actor {
 *     tls {
 *         enabled = true
 *         keystore {
 *             path = '/etc/myapp/certs/keystore.jks'
 *             password = System.getenv('KEYSTORE_PASSWORD')
 *         }
 *         truststore {
 *             path = '/etc/myapp/certs/truststore.jks'
 *             password = System.getenv('TRUSTSTORE_PASSWORD')
 *         }
 *         protocols = ['TLSv1.3', 'TLSv1.2']
 *     }
 * }
 */
def configFileExample() {
    // Load configuration
    def configFile = new File('tls-config.groovy')
    def config = new ConfigSlurper().parse(configFile.toURI().toURL())

    def tlsConfig = new RSocketTransport.TlsConfig(
            enabled: config.actor.tls.enabled,
            keyStorePath: config.actor.tls.keystore.path,
            keyStorePassword: config.actor.tls.keystore.password,
            trustStorePath: config.actor.tls.truststore.path,
            trustStorePassword: config.actor.tls.truststore.password,
            protocols: config.actor.tls.protocols
    )

    def transport = new RSocketTransport(actorSystem, 7000, true, tlsConfig)
    transport.start()
}

// =============================================================================
// EXAMPLE 6: Multi-Environment Configuration
// =============================================================================

/**
 * Different certificates for dev, staging, and production.
 */
def multiEnvironmentExample() {
    def environment = System.getenv('APP_ENV') ?: 'development'

    def tlsConfig

    switch (environment) {
        case 'development':
            tlsConfig = new RSocketTransport.TlsConfig(
                    enabled: true,
                    keyStorePath: '/test-certs/server-keystore.jks',
                    keyStorePassword: 'changeit',
                    trustStorePath: '/test-certs/truststore.jks',
                    trustStorePassword: 'changeit',
                    developmentMode: true
            )
            break

        case 'staging':
            tlsConfig = new RSocketTransport.TlsConfig(
                    enabled: true,
                    keyStorePath: '/certs/staging-keystore.jks',
                    keyStorePassword: System.getenv('STAGING_KEYSTORE_PASSWORD'),
                    trustStorePath: '/certs/staging-truststore.jks',
                    trustStorePassword: System.getenv('STAGING_TRUSTSTORE_PASSWORD')
            )
            break

        case 'production':
            tlsConfig = new RSocketTransport.TlsConfig(
                    enabled: true,
                    keyStorePath: System.getenv('PROD_TLS_KEYSTORE_PATH'),
                    keyStorePassword: System.getenv('PROD_KEYSTORE_PASSWORD'),
                    trustStorePath: System.getenv('PROD_TLS_TRUSTSTORE_PATH'),
                    trustStorePassword: System.getenv('PROD_TRUSTSTORE_PASSWORD'),
                    protocols: ['TLSv1.3']  // Production: only TLS 1.3
            )
            break
    }

    def transport = new RSocketTransport(actorSystem, 7000, true, tlsConfig)
    transport.start()
}

// =============================================================================
// EXAMPLE 7: Client-Only Configuration (No Server Certificate)
// =============================================================================

/**
 * For clients connecting to a secure server, you only need the truststore.
 */
def clientOnlyExample() {
    def tlsConfig = new RSocketTransport.TlsConfig(
            enabled: true,
            // No keyStorePath needed for client-only
            trustStorePath: '/certs/truststore.jks',
            trustStorePassword: System.getenv('TRUSTSTORE_PASSWORD')
    )

    def transport = new RSocketTransport(null, 0, false, tlsConfig)
    transport.start()

    // Connect to secure server
    def future = transport.ask(
            "rsocket://secure-server:7000/system/actor",
            "Hello",
            Duration.ofSeconds(5)
    )
}

// =============================================================================
// EXAMPLE 8: Mutual TLS (mTLS) Configuration
// =============================================================================

/**
 * For mutual TLS, both client and server need certificates.
 */
def mutualTlsExample() {
    // Server configuration
    def serverTlsConfig = new RSocketTransport.TlsConfig(
            enabled: true,
            keyStorePath: '/certs/server-keystore.jks',
            keyStorePassword: System.getenv('SERVER_KEYSTORE_PASSWORD'),
            trustStorePath: '/certs/truststore.jks',  // Contains trusted client certs
            trustStorePassword: System.getenv('TRUSTSTORE_PASSWORD'),
            protocols: ['TLSv1.3', 'TLSv1.2']
    )

    // Client configuration
    def clientTlsConfig = new RSocketTransport.TlsConfig(
            enabled: true,
            keyStorePath: '/certs/client-keystore.jks',  // Client certificate
            keyStorePassword: System.getenv('CLIENT_KEYSTORE_PASSWORD'),
            trustStorePath: '/certs/truststore.jks',  // Contains trusted server certs
            trustStorePassword: System.getenv('TRUSTSTORE_PASSWORD')
    )
}

// =============================================================================
// EXAMPLE 9: Using CertificateResolver with Custom Resolution
// =============================================================================

/**
 * Advanced: Use CertificateResolver for flexible path resolution.
 */
def certificateResolverExample() {
    // Create resolver with custom configuration
    def config = new ConfigSlurper().parse('''
        actor.tls.keystore.path = '/certs/keystore.jks'
        actor.tls.truststore.path = '/certs/truststore.jks'
    ''')

    def resolver = new CertificateResolver(config, false)

    // Resolve keystore path using multiple strategies
    def keystorePath = resolver.resolve(
            null,                                  // No explicit path
            'actor.tls.keystore.path',            // Try system property
            'ACTOR_TLS_KEYSTORE_PATH',            // Try environment variable
            '/certs/keystore.jks'                 // Try classpath resource
    )

    if (!keystorePath) {
        throw new IllegalStateException("Could not resolve keystore path!")
    }

    // Use with TlsContextBuilder
    def sslContext = TlsContextBuilder.builder()
            .useResolver(true)
            .resolver(resolver)
            .keyStore(keystorePath, System.getenv('KEYSTORE_PASSWORD'))
            .trustStore('/certs/truststore.jks', System.getenv('TRUSTSTORE_PASSWORD'))
            .protocols(['TLSv1.3', 'TLSv1.2'])
            .build()
}

// =============================================================================
// EXAMPLE 10: Docker/Kubernetes Deployment with Secrets
// =============================================================================

/**
 * For containerized deployments, mount certificates as secrets.
 *
 * Kubernetes deployment.yaml:
 *
 * volumes:
 *   - name: tls-certs
 *     secret:
 *       secretName: actor-tls-certs
 *
 * volumeMounts:
 *   - name: tls-certs
 *     mountPath: /etc/tls
 *     readOnly: true
 *
 * env:
 *   - name: ACTOR_TLS_KEYSTORE_PATH
 *     value: /etc/tls/keystore.jks
 *   - name: KEYSTORE_PASSWORD
 *     valueFrom:
 *       secretKeyRef:
 *         name: tls-passwords
 *         key: keystore-password
 */
def kubernetesExample() {
    def tlsConfig = new RSocketTransport.TlsConfig(
            enabled: true,
            keyStorePath: System.getenv('ACTOR_TLS_KEYSTORE_PATH') ?: '/etc/tls/keystore.jks',
            keyStorePassword: System.getenv('KEYSTORE_PASSWORD'),
            trustStorePath: System.getenv('ACTOR_TLS_TRUSTSTORE_PATH') ?: '/etc/tls/truststore.jks',
            trustStorePassword: System.getenv('TRUSTSTORE_PASSWORD')
    )

    def transport = new RSocketTransport(actorSystem, 7000, true, tlsConfig)
    transport.start()
}

// =============================================================================
// EXAMPLE 11: Graceful Fallback Strategy
// =============================================================================

/**
 * Try multiple certificate locations with graceful fallback.
 */
def fallbackStrategyExample() {
    def tlsConfig

    // Try production certificates first
    if (System.getenv('PROD_TLS_KEYSTORE_PATH')) {
        tlsConfig = new RSocketTransport.TlsConfig(
                enabled: true,
                keyStorePath: System.getenv('PROD_TLS_KEYSTORE_PATH'),
                keyStorePassword: System.getenv('PROD_KEYSTORE_PASSWORD'),
                trustStorePath: System.getenv('PROD_TLS_TRUSTSTORE_PATH'),
                trustStorePassword: System.getenv('PROD_TRUSTSTORE_PASSWORD')
        )
    }
    // Try classpath resources
    else if (this.class.getResource('/certs/keystore.jks')) {
        tlsConfig = new RSocketTransport.TlsConfig(
                enabled: true,
                keyStorePath: '/certs/keystore.jks',
                keyStorePassword: System.getenv('KEYSTORE_PASSWORD') ?: 'changeit',
                trustStorePath: '/certs/truststore.jks',
                trustStorePassword: System.getenv('TRUSTSTORE_PASSWORD') ?: 'changeit'
        )
    }
    // Fallback to test certificates in development
    else {
        println "⚠️  WARNING: Using test certificates!"
        tlsConfig = new RSocketTransport.TlsConfig(
                enabled: true,
                keyStorePath: '/test-certs/server-keystore.jks',
                keyStorePassword: 'changeit',
                trustStorePath: '/test-certs/truststore.jks',
                trustStorePassword: 'changeit',
                developmentMode: true
        )
    }

    def transport = new RSocketTransport(actorSystem, 7000, true, tlsConfig)
    transport.start()
}

// =============================================================================
// EXAMPLE 12: Validation and Error Handling
// =============================================================================

/**
 * Proper validation and error handling for certificate configuration.
 */
def validationExample() {
    try {
        def keystorePath = System.getenv('ACTOR_TLS_KEYSTORE_PATH')
        def keystorePassword = System.getenv('KEYSTORE_PASSWORD')

        // Validate configuration
        if (!keystorePath) {
            throw new IllegalArgumentException(
                    "Keystore path not configured. Set ACTOR_TLS_KEYSTORE_PATH environment variable."
            )
        }

        if (!keystorePassword) {
            throw new IllegalArgumentException(
                    "Keystore password not configured. Set KEYSTORE_PASSWORD environment variable."
            )
        }

        // Validate that file exists (if filesystem path)
        def keystoreFile = new File(keystorePath)
        if (!keystorePath.startsWith('/') && !keystoreFile.exists()) {
            throw new FileNotFoundException(
                    "Keystore file not found: ${keystorePath}"
            )
        }

        def tlsConfig = new RSocketTransport.TlsConfig(
                enabled: true,
                keyStorePath: keystorePath,
                keyStorePassword: keystorePassword,
                trustStorePath: System.getenv('ACTOR_TLS_TRUSTSTORE_PATH'),
                trustStorePassword: System.getenv('TRUSTSTORE_PASSWORD')
        )

        def transport = new RSocketTransport(actorSystem, 7000, true, tlsConfig)
        transport.start()

        println "✅ TLS transport started successfully"

    } catch (Exception e) {
        println "❌ Failed to start TLS transport: ${e.message}"
        println "Please check your certificate configuration."
        e.printStackTrace()
        throw e
    }
}

// =============================================================================
// Best Practices Summary
// =============================================================================

/**
 * BEST PRACTICES:
 *
 * 1. NEVER hardcode passwords in source code
 *    ✅ Use: Environment variables, system properties, or secret management
 *    ❌ Don't: keyStorePassword: 'mypassword'
 *
 * 2. Use different certificates per environment
 *    ✅ Dev: Test certificates (bundled)
 *    ✅ Staging: Staging certificates (classpath or mounted)
 *    ✅ Production: CA-signed certificates (externally managed)
 *
 * 3. Keep certificates in appropriate locations
 *    ✅ Dev: test-certs/ directory or classpath
 *    ✅ Production: Mounted volumes, secrets, or secure filesystem
 *
 * 4. Use proper certificate validation
 *    ✅ Always validate paths and passwords before use
 *    ✅ Provide clear error messages when certificates can't be found
 *
 * 5. Rotate certificates regularly
 *    ✅ Follow your organization's certificate rotation policy
 *    ✅ Use certificates with appropriate expiration dates
 *
 * 6. Use strong protocols
 *    ✅ TLSv1.3 for production
 *    ✅ TLSv1.2 as fallback only if needed
 *    ❌ Never use TLSv1.0 or TLSv1.1
 *
 * 7. Protect private keys
 *    ✅ Use appropriate file permissions (600)
 *    ✅ Store in secure locations
 *    ✅ Never commit to version control
 *
 * 8. Document configuration clearly
 *    ✅ Provide README with setup instructions
 *    ✅ Include example configurations
 *    ✅ Document all environment variables
 */