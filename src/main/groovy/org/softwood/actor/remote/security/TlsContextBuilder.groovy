package org.softwood.actor.remote.security

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

import javax.net.ssl.*
import java.security.KeyStore
import java.security.SecureRandom

/**
 * SSL/TLS context builder for secure actor communication.
 * 
 * <p>Provides utilities for creating SSL contexts with custom keystores
 * and truststores for encrypted communication.</p>
 * 
 * <h2>Usage</h2>
 * <pre>
 * def sslContext = TlsContextBuilder.builder()
 *     .keyStore('/path/to/keystore.jks', 'password')
 *     .trustStore('/path/to/truststore.jks', 'password')
 *     .protocols(['TLSv1.3', 'TLSv1.2'])
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
    
    /**
     * Creates a new TLS context builder.
     */
    static TlsContextBuilder builder() {
        return new TlsContextBuilder()
    }
    
    /**
     * Sets the keystore containing the server's certificate and private key.
     * 
     * @param path path to keystore file
     * @param password keystore password
     * @return this builder
     */
    TlsContextBuilder keyStore(String path, String password) {
        this.keyStorePath = path
        this.keyStorePassword = password.toCharArray()
        return this
    }
    
    /**
     * Sets the truststore containing trusted CA certificates.
     * 
     * @param path path to truststore file
     * @param password truststore password
     * @return this builder
     */
    TlsContextBuilder trustStore(String path, String password) {
        this.trustStorePath = path
        this.trustStorePassword = password.toCharArray()
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
        
        // Create SSL context
        SSLContext sslContext = SSLContext.getInstance('TLS')
        
        // Load keystore (server certificate)
        KeyManager[] keyManagers = null
        if (keyStorePath) {
            keyManagers = loadKeyManagers(keyStorePath, keyStorePassword)
        }
        
        // Load truststore (trusted CAs)
        TrustManager[] trustManagers = null
        if (trustStorePath) {
            trustManagers = loadTrustManagers(trustStorePath, trustStorePassword)
        }
        
        // Initialize SSL context
        sslContext.init(keyManagers, trustManagers, new SecureRandom())
        
        log.info("SSL context created successfully")
        return sslContext
    }
    
    /**
     * Loads key managers from keystore.
     */
    private KeyManager[] loadKeyManagers(String keystorePath, char[] password) throws Exception {
        log.debug("Loading keystore from: ${keystorePath}")
        
        // Load keystore
        KeyStore keyStore = KeyStore.getInstance('JKS')
        new FileInputStream(keystorePath).withCloseable { fis ->
            keyStore.load(fis, password)
        }
        
        // Initialize key manager factory
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm()
        )
        kmf.init(keyStore, password)
        
        return kmf.getKeyManagers()
    }
    
    /**
     * Loads trust managers from truststore.
     */
    private TrustManager[] loadTrustManagers(String truststorePath, char[] password) throws Exception {
        log.debug("Loading truststore from: ${truststorePath}")
        
        // Load truststore
        KeyStore trustStore = KeyStore.getInstance('JKS')
        new FileInputStream(truststorePath).withCloseable { fis ->
            trustStore.load(fis, password)
        }
        
        // Initialize trust manager factory
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        )
        tmf.init(trustStore)
        
        return tmf.getTrustManagers()
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
