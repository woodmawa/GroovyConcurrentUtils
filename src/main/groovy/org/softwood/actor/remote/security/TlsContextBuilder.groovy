package org.softwood.actor.remote.security

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import javax.net.ssl.*
import java.security.KeyStore
import java.security.SecureRandom

/**
 * Enhanced SSL/TLS context builder for secure actor communication.
 * 
 * <p>Provides utilities for creating SSL contexts with custom keystores
 * and truststores for encrypted communication. Supports both filesystem
 * paths and classpath resources.</p>
 * 
 * <h2>Usage</h2>
 * <pre>
 * // Using filesystem paths
 * def sslContext = TlsContextBuilder.builder()
 *     .keyStore('/path/to/keystore.jks', 'password')
 *     .trustStore('/path/to/truststore.jks', 'password')
 *     .protocols(['TLSv1.3', 'TLSv1.2'])
 *     .build()
 * 
 * // Using classpath resources
 * def sslContext = TlsContextBuilder.builder()
 *     .keyStore('/certs/keystore.jks', 'password')
 *     .trustStore('/certs/truststore.jks', 'password')
 *     .build()
 * 
 * // Using certificate resolver
 * def sslContext = TlsContextBuilder.builder()
 *     .useResolver(true)
 *     .keyStoreProperty('actor.tls.keystore.path', 'password')
 *     .trustStoreProperty('actor.tls.truststore.path', 'password')
 *     .build()
 * </pre>
 * 
 * @since 2.0.0
 */
@Slf4j
@CompileStatic
class TlsContextBuilder {
    
    private String keyStorePath
    private char[] keyStorePassword
    private String trustStorePath
    private char[] trustStorePassword
    private List<String> protocols = ['TLSv1.3', 'TLSv1.2']
    private List<String> cipherSuites = []
    
    // Certificate resolution support
    private boolean useResolver = false
    private CertificateResolver resolver
    private String keyStorePropertyName
    private String keyStoreEnvVar
    private String trustStorePropertyName
    private String trustStoreEnvVar
    private boolean developmentMode = false
    
    /**
     * Creates a new TLS context builder.
     */
    static TlsContextBuilder builder() {
        return new TlsContextBuilder()
    }
    
    /**
     * Enables certificate resolution using CertificateResolver.
     * 
     * @param enabled if true, use resolver to find certificates
     * @return this builder
     */
    TlsContextBuilder useResolver(boolean enabled) {
        this.useResolver = enabled
        return this
    }
    
    /**
     * Sets development mode for certificate resolution.
     * In development mode, bundled test certificates can be used as fallback.
     * 
     * @param enabled if true, allow test certificates in dev
     * @return this builder
     */
    TlsContextBuilder developmentMode(boolean enabled) {
        this.developmentMode = enabled
        return this
    }
    
    /**
     * Sets a custom certificate resolver.
     * 
     * @param resolver the resolver to use
     * @return this builder
     */
    TlsContextBuilder resolver(CertificateResolver resolver) {
        this.resolver = resolver
        this.useResolver = true
        return this
    }
    
    /**
     * Sets the keystore containing the server's certificate and private key.
     * Supports both filesystem paths and classpath resources.
     * 
     * @param path path to keystore file (filesystem or classpath)
     * @param password keystore password
     * @return this builder
     */
    TlsContextBuilder keyStore(String path, String password) {
        this.keyStorePath = path
        this.keyStorePassword = password.toCharArray()
        return this
    }
    
    /**
     * Sets keystore properties for resolution.
     * 
     * @param propertyName system property name
     * @param password keystore password
     * @return this builder
     */
    TlsContextBuilder keyStoreProperty(String propertyName, String password) {
        this.keyStorePropertyName = propertyName
        this.keyStorePassword = password.toCharArray()
        this.useResolver = true
        return this
    }
    
    /**
     * Sets keystore resolution from environment variable.
     * 
     * @param envVar environment variable name
     * @param password keystore password
     * @return this builder
     */
    TlsContextBuilder keyStoreEnv(String envVar, String password) {
        this.keyStoreEnvVar = envVar
        this.keyStorePassword = password.toCharArray()
        this.useResolver = true
        return this
    }
    
    /**
     * Sets the truststore containing trusted CA certificates.
     * Supports both filesystem paths and classpath resources.
     * 
     * @param path path to truststore file (filesystem or classpath)
     * @param password truststore password
     * @return this builder
     */
    TlsContextBuilder trustStore(String path, String password) {
        this.trustStorePath = path
        this.trustStorePassword = password.toCharArray()
        return this
    }
    
    /**
     * Sets truststore properties for resolution.
     * 
     * @param propertyName system property name
     * @param password truststore password
     * @return this builder
     */
    TlsContextBuilder trustStoreProperty(String propertyName, String password) {
        this.trustStorePropertyName = propertyName
        this.trustStorePassword = password.toCharArray()
        this.useResolver = true
        return this
    }
    
    /**
     * Sets truststore resolution from environment variable.
     * 
     * @param envVar environment variable name
     * @param password truststore password
     * @return this builder
     */
    TlsContextBuilder trustStoreEnv(String envVar, String password) {
        this.trustStoreEnvVar = envVar
        this.trustStorePassword = password.toCharArray()
        this.useResolver = true
        return this
    }
    
    /**
     * Sets allowed TLS protocols.
     * 
     * @param protocols list of protocols (e.g., 'TLSv1.3', 'TLSv1.2')
     * @return this builder
     */
    TlsContextBuilder protocols(List<String> protocols) {
        this.protocols = protocols
        return this
    }
    
    /**
     * Sets allowed cipher suites.
     * 
     * @param cipherSuites list of cipher suites
     * @return this builder
     */
    TlsContextBuilder cipherSuites(List<String> cipherSuites) {
        this.cipherSuites = cipherSuites
        return this
    }
    
    /**
     * Builds the SSL context.
     * 
     * @return configured SSLContext
     * @throws Exception if SSL context creation fails
     */
    SSLContext build() throws Exception {
        log.info("Building SSL context with protocols: ${protocols}")
        
        // Initialize resolver if needed
        if (useResolver && !resolver) {
            resolver = new CertificateResolver(null, developmentMode)
        }
        
        // Resolve paths if using resolver
        String resolvedKeyStorePath = keyStorePath
        String resolvedTrustStorePath = trustStorePath
        
        if (useResolver && resolver) {
            if (keyStorePropertyName || keyStoreEnvVar) {
                resolvedKeyStorePath = resolver.resolve(
                    keyStorePath,
                    keyStorePropertyName ?: 'actor.tls.keystore.path',
                    keyStoreEnvVar ?: 'ACTOR_TLS_KEYSTORE_PATH',
                    '/certs/keystore.jks'
                )
                if (!resolvedKeyStorePath) {
                    throw new IllegalStateException(
                        "Keystore not found. Tried property: ${keyStorePropertyName}, env: ${keyStoreEnvVar}"
                    )
                }
            }
            
            if (trustStorePropertyName || trustStoreEnvVar) {
                resolvedTrustStorePath = resolver.resolve(
                    trustStorePath,
                    trustStorePropertyName ?: 'actor.tls.truststore.path',
                    trustStoreEnvVar ?: 'ACTOR_TLS_TRUSTSTORE_PATH',
                    '/certs/truststore.jks'
                )
                if (!resolvedTrustStorePath) {
                    throw new IllegalStateException(
                        "Truststore not found. Tried property: ${trustStorePropertyName}, env: ${trustStoreEnvVar}"
                    )
                }
            }
        }
        
        // Create SSL context
        SSLContext sslContext = SSLContext.getInstance('TLS')
        
        // Load keystore (server certificate)
        KeyManager[] keyManagers = null
        if (resolvedKeyStorePath) {
            keyManagers = loadKeyManagers(resolvedKeyStorePath, keyStorePassword)
        }
        
        // Load truststore (trusted CAs)
        TrustManager[] trustManagers = null
        if (resolvedTrustStorePath) {
            trustManagers = loadTrustManagers(resolvedTrustStorePath, trustStorePassword)
        }
        
        // Initialize SSL context
        sslContext.init(keyManagers, trustManagers, new SecureRandom())
        
        log.info("SSL context created successfully")
        return sslContext
    }
    
    /**
     * Loads key managers from keystore.
     * Supports both filesystem paths and classpath resources.
     */
    private KeyManager[] loadKeyManagers(String keystorePath, char[] password) throws Exception {
        log.debug("Loading keystore from: ${keystorePath}")
        
        // Load keystore
        KeyStore keyStore = KeyStore.getInstance('JKS')
        
        // Open input stream (supports both file and classpath)
        InputStream inputStream = openInputStream(keystorePath)
        if (!inputStream) {
            throw new FileNotFoundException("Keystore not found: ${keystorePath}")
        }
        
        try {
            keyStore.load(inputStream, password)
        } finally {
            inputStream.close()
        }
        
        // Initialize key manager factory
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm()
        )
        kmf.init(keyStore, password)
        
        log.info("Keystore loaded successfully from: ${keystorePath}")
        return kmf.getKeyManagers()
    }
    
    /**
     * Loads trust managers from truststore.
     * Supports both filesystem paths and classpath resources.
     */
    private TrustManager[] loadTrustManagers(String truststorePath, char[] password) throws Exception {
        log.debug("Loading truststore from: ${truststorePath}")
        
        // Load truststore
        KeyStore trustStore = KeyStore.getInstance('JKS')
        
        // Open input stream (supports both file and classpath)
        InputStream inputStream = openInputStream(truststorePath)
        if (!inputStream) {
            throw new FileNotFoundException("Truststore not found: ${truststorePath}")
        }
        
        try {
            trustStore.load(inputStream, password)
        } finally {
            inputStream.close()
        }
        
        // Initialize trust manager factory
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        tmf.init(trustStore)
        
        log.info("Truststore loaded successfully from: ${truststorePath}")
        return tmf.getTrustManagers()
    }
    
    /**
     * Opens an input stream for a path that could be filesystem or classpath.
     */
    private InputStream openInputStream(String path) {
        if (!path) return null
        
        // If using resolver, use it to open the stream
        if (resolver) {
            return resolver.openStream(path)
        }
        
        // Try as file first
        try {
            def file = new File(path)
            if (file.exists() && file.canRead()) {
                return new FileInputStream(file)
            }
        } catch (Exception e) {
            log.trace("Not a readable file: ${path}", e)
        }
        
        // Try as classpath resource
        try {
            def normalizedPath = path.startsWith('/') ? path : '/' + path
            def stream = this.class.getResourceAsStream(normalizedPath)
            if (stream) {
                return stream
            }
        } catch (Exception e) {
            log.trace("Not a classpath resource: ${path}", e)
        }
        
        return null
    }
    
    /**
     * Creates an SSL socket factory with custom protocols and cipher suites.
     */
    SSLSocketFactory createSocketFactory(SSLContext context) {
        SSLSocketFactory factory = context.getSocketFactory()
        
        if (protocols || cipherSuites) {
            factory = new CustomSSLSocketFactory(
                factory, 
                protocols as String[], 
                cipherSuites as String[]
            )
        }
        
        return factory
    }
    
    /**
     * Custom SSL socket factory that enforces specific protocols and cipher suites.
     */
    private static class CustomSSLSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate
        private final String[] protocols
        private final String[] cipherSuites
        
        CustomSSLSocketFactory(SSLSocketFactory delegate, String[] protocols, String[] cipherSuites) {
            this.delegate = delegate
            this.protocols = protocols
            this.cipherSuites = cipherSuites
        }
        
        @Override
        Socket createSocket(String host, int port) throws IOException {
            return configure(delegate.createSocket(host, port))
        }
        
        @Override
        Socket createSocket(String host, int port, InetAddress localhost, int localPort) throws IOException {
            return configure(delegate.createSocket(host, port, localhost, localPort))
        }
        
        @Override
        Socket createSocket(InetAddress host, int port) throws IOException {
            return configure(delegate.createSocket(host, port))
        }
        
        @Override
        Socket createSocket(InetAddress host, int port, InetAddress localhost, int localPort) throws IOException {
            return configure(delegate.createSocket(host, port, localhost, localPort))
        }
        
        @Override
        Socket createSocket(Socket socket, String host, int port, boolean autoClose) throws IOException {
            return configure(delegate.createSocket(socket, host, port, autoClose))
        }
        
        @Override
        String[] getDefaultCipherSuites() {
            return cipherSuites ?: delegate.getDefaultCipherSuites()
        }
        
        @Override
        String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites()
        }
        
        private Socket configure(Socket socket) {
            if (socket instanceof SSLSocket) {
                SSLSocket sslSocket = (SSLSocket) socket
                if (protocols) {
                    sslSocket.setEnabledProtocols(protocols)
                }
                if (cipherSuites) {
                    sslSocket.setEnabledCipherSuites(cipherSuites)
                }
            }
            return socket
        }
    }
}
