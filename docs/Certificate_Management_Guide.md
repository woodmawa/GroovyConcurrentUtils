# Certificate Management for Secure Communication

## Overview

GroovyConcurrentUtils supports secure TLS/SSL-encrypted communication across multiple components:
- **Remote Actor Communication** - Encrypted RSocket connections between actor systems
- **Hazelcast Clustering** - Encrypted communication between cluster nodes

All components use a **unified certificate management approach** with flexible configuration via the `CertificateResolver` for consistent, production-ready security.

## Quick Start

### Development (Test Certificates)

For development and testing, use classpath resources with test certificates:

**Remote Actors:**
```groovy
def actorTls = new RSocketTransport.TlsConfig(
    enabled: true,
    keyStorePath: '/test-certs/actor-server-keystore.jks',
    keyStorePassword: 'changeit',
    trustStorePath: '/test-certs/truststore.jks',
    trustStorePassword: 'changeit',
    developmentMode: true
)
```

**Hazelcast Clustering:**
```groovy
def hazelcastSecurity = HazelcastSecurityConfig.builder()
    .tlsEnabled(true)
    .developmentMode(true)
    .keyStoreClasspathResource('/test-certs/hazelcast-keystore.jks')
    .keyStorePassword('changeit')
    .trustStoreClasspathResource('/test-certs/truststore.jks')
    .trustStorePassword('changeit')
    .build()
```

### Production (Environment Variables)

**Remote Actors:**
```groovy
// Set environment: ACTOR_TLS_KEYSTORE_PATH=/etc/certs/actor-server-keystore.jks
def actorTls = RSocketTransport.TlsConfig.fromEnvironment()
```

**Hazelcast Clustering:**
```groovy
// Set environment: HAZELCAST_TLS_KEYSTORE_PATH=/etc/certs/hazelcast-keystore.jks
def hazelcastSecurity = HazelcastSecurityConfig.fromEnvironment()
```

## Certificate Resolution Strategy

The library uses `CertificateResolver` with a priority-based resolution strategy to find certificates:

1. **Explicit Path** (highest priority) - Direct path provided in code
2. **System Property** - Java system property (`-Dactor.tls.keystore.path=...` or `-Dhazelcast.tls.keystore.path=...`)
3. **Environment Variable** - OS environment variable (`ACTOR_TLS_KEYSTORE_PATH` or `HAZELCAST_TLS_KEYSTORE_PATH`)
4. **Config File** - Groovy ConfigSlurper configuration
5. **Classpath Resource** - Resource in JAR/classpath (`/certs/keystore.jks`)
6. **Test Certificates** (dev mode only) - Bundled test certificates in `/test-certs/`

## Configuration Methods

### 1. Classpath Resources (Recommended for Development)

Place certificates in your project's resources:

```
my-project/
├── src/main/resources/
│   └── certs/
│       ├── actor-server-keystore.jks
│       ├── hazelcast-keystore.jks
│       └── truststore.jks
```

**Remote Actors:**
```groovy
def actorTls = new RSocketTransport.TlsConfig(
    enabled: true,
    keyStorePath: '/certs/actor-server-keystore.jks',      // Classpath resource
    keyStorePassword: System.getenv('ACTOR_KEYSTORE_PASSWORD'),
    trustStorePath: '/certs/truststore.jks',
    trustStorePassword: System.getenv('TRUSTSTORE_PASSWORD')
)
```

**Hazelcast:**
```groovy
def hazelcastSecurity = HazelcastSecurityConfig.builder()
    .tlsEnabled(true)
    .keyStoreClasspathResource('/certs/hazelcast-keystore.jks')  // Classpath resource
    .keyStorePassword(System.getenv('HAZELCAST_KEYSTORE_PASSWORD'))
    .trustStoreClasspathResource('/certs/truststore.jks')
    .trustStorePassword(System.getenv('TRUSTSTORE_PASSWORD'))
    .build()
```

**Advantages:**
- Certificates bundled with application
- Works in containers and cloud deployments
- Simple deployment

### 2. System Properties

Configure via command line:

**Remote Actors:**
```bash
java -Dactor.tls.keystore.path=/etc/myapp/certs/actor-keystore.jks \
     -Dactor.tls.truststore.path=/etc/myapp/certs/truststore.jks \
     -jar myapp.jar
```

**Hazelcast:**
```bash
java -Dhazelcast.tls.keystore.path=/etc/myapp/certs/hazelcast-keystore.jks \
     -Dhazelcast.tls.truststore.path=/etc/myapp/certs/truststore.jks \
     -jar myapp.jar
```

**Advantages:**
- Override at runtime
- No code changes needed
- Good for testing different certificates

### 3. Environment Variables (Recommended for Production)

Configure via environment:

**Remote Actors:**
```bash
export ACTOR_TLS_KEYSTORE_PATH=/etc/myapp/certs/actor-server-keystore.jks
export ACTOR_KEYSTORE_PASSWORD=***SECURE***
export ACTOR_TLS_TRUSTSTORE_PATH=/etc/myapp/certs/truststore.jks
export TRUSTSTORE_PASSWORD=***SECURE***
```

**Hazelcast:**
```bash
export HAZELCAST_TLS_KEYSTORE_PATH=/etc/myapp/certs/hazelcast-keystore.jks
export HAZELCAST_KEYSTORE_PASSWORD=***SECURE***
export HAZELCAST_TLS_TRUSTSTORE_PATH=/etc/myapp/certs/truststore.jks
export HAZELCAST_TRUSTSTORE_PASSWORD=***SECURE***
```

**Code:**
```groovy
// Automatically picks up environment variables
def actorTls = RSocketTransport.TlsConfig.fromEnvironment()
def hazelcastSecurity = HazelcastSecurityConfig.fromEnvironment()
```

**Advantages:**
- Standard 12-factor app approach
- Container/Kubernetes friendly
- Secure secret management

### 4. Explicit Paths

Provide paths directly in code:

**Remote Actors:**
```groovy
def actorTls = new RSocketTransport.TlsConfig(
    enabled: true,
    keyStorePath: '/absolute/path/to/actor-keystore.jks',
    keyStorePassword: 'mypassword',
    trustStorePath: '/absolute/path/to/truststore.jks',
    trustStorePassword: 'mypassword'
)
```

**Hazelcast:**
```groovy
def hazelcastSecurity = HazelcastSecurityConfig.builder()
    .tlsEnabled(true)
    .keyStoreExplicitPath('/absolute/path/to/hazelcast-keystore.jks')
    .keyStorePassword('mypassword')
    .trustStoreExplicitPath('/absolute/path/to/truststore.jks')
    .trustStorePassword('mypassword')
    .build()
```

**Advantages:**
- Full control
- Simple for single environment
- Easy debugging

### 5. Unified Configuration Files (Recommended for Multi-Component Apps)

Create a unified `tls-config.groovy`:

```groovy
// Unified TLS configuration for all components
tls {
    development = false  // Set to true for development mode
    
    // Remote Actor Configuration
    actor {
        enabled = true
        keystore {
            path = System.getenv('ACTOR_TLS_KEYSTORE_PATH') ?: '/certs/actor-server-keystore.jks'
            password = System.getenv('ACTOR_KEYSTORE_PASSWORD')
            type = 'JKS'
        }
        truststore {
            path = System.getenv('ACTOR_TLS_TRUSTSTORE_PATH') ?: '/certs/truststore.jks'
            password = System.getenv('TRUSTSTORE_PASSWORD')
            type = 'JKS'
        }
        protocols = ['TLSv1.3', 'TLSv1.2']
    }
    
    // Hazelcast Clustering Configuration
    hazelcast {
        enabled = true
        keystore {
            path = System.getenv('HAZELCAST_TLS_KEYSTORE_PATH') ?: '/certs/hazelcast-keystore.jks'
            password = System.getenv('HAZELCAST_KEYSTORE_PASSWORD')
            type = 'JKS'
        }
        truststore {
            path = System.getenv('HAZELCAST_TLS_TRUSTSTORE_PATH') ?: '/certs/truststore.jks'
            password = System.getenv('HAZELCAST_TRUSTSTORE_PASSWORD')
            type = 'JKS'
        }
        protocols = ['TLSv1.3']  // Production: only TLS 1.3
        
        // Additional Hazelcast security
        authentication {
            enabled = true
            username = System.getenv('HAZELCAST_USERNAME')
            password = System.getenv('HAZELCAST_PASSWORD')
        }
        messageSigning {
            enabled = true
            key = System.getenv('HAZELCAST_SIGNING_KEY')
            algorithm = 'HmacSHA256'
        }
    }
}
```

Load and use:

```groovy
def config = new ConfigSlurper().parse(new File('tls-config.groovy').toURI().toURL())

// Remote actors
def actorTls = RSocketTransport.TlsConfig.fromConfig(config.tls.actor)

// Hazelcast
def hazelcastSecurity = HazelcastSecurityConfig.fromConfig(config.tls.hazelcast)
```

**Advantages:**
- Single source of truth for all certificates
- Environment-specific configurations
- Shared truststore management
- Easy to version control (excluding secrets)

## Component-Specific Configuration

### Remote Actor Security

**Server Configuration (Has Certificate):**

```groovy
def serverTls = new RSocketTransport.TlsConfig(
    enabled: true,
    keyStorePath: '/certs/actor-server-keystore.jks',      // Server certificate
    keyStorePassword: System.getenv('SERVER_KEYSTORE_PASSWORD'),
    trustStorePath: '/certs/truststore.jks',               // Trusted clients (for mTLS)
    trustStorePassword: System.getenv('TRUSTSTORE_PASSWORD'),
    developmentMode: false
)
```

**Client Configuration (Connects to Server):**

```groovy
def clientTls = new RSocketTransport.TlsConfig(
    enabled: true,
    // No keyStorePath needed for basic TLS
    trustStorePath: '/certs/truststore.jks',               // Trust server certificate
    trustStorePassword: System.getenv('TRUSTSTORE_PASSWORD'),
    developmentMode: false
)
```

**Mutual TLS (mTLS) Client:**

```groovy
def clientTls = new RSocketTransport.TlsConfig(
    enabled: true,
    keyStorePath: '/certs/client-keystore.jks',            // Client certificate
    keyStorePassword: System.getenv('CLIENT_KEYSTORE_PASSWORD'),
    trustStorePath: '/certs/truststore.jks',               // Trust server certificate
    trustStorePassword: System.getenv('TRUSTSTORE_PASSWORD'),
    developmentMode: false
)
```

### Hazelcast Clustering Security

**Full Security (TLS + Authentication + Message Signing):**

```groovy
def hazelcastSecurity = HazelcastSecurityConfig.secure(
    null,                                                   // Use resolver (env vars, classpath, etc.)
    System.getenv('HAZELCAST_KEYSTORE_PASSWORD'),
    System.getenv('HAZELCAST_USERNAME'),
    System.getenv('HAZELCAST_PASSWORD'),
    System.getenv('HAZELCAST_SIGNING_KEY'),
    ['10.0.1.10', '10.0.1.11', '10.0.1.12']                // Allowed cluster members
)
```

**TLS Only (No Authentication):**

```groovy
def hazelcastSecurity = HazelcastSecurityConfig.builder()
    .tlsEnabled(true)
    .keyStoreClasspathResource('/certs/hazelcast-keystore.jks')
    .keyStorePassword(System.getenv('HAZELCAST_KEYSTORE_PASSWORD'))
    .trustStoreClasspathResource('/certs/truststore.jks')
    .trustStorePassword(System.getenv('TRUSTSTORE_PASSWORD'))
    .authenticationEnabled(false)
    .messageSigningEnabled(false)
    .build()
```

**Development Mode (Test Certificates):**

```groovy
def hazelcastSecurity = HazelcastSecurityConfig.builder()
    .tlsEnabled(true)
    .developmentMode(true)  // Enables fallback to /test-certs/
    .keyStorePassword('changeit')
    .trustStorePassword('changeit')
    .build()
```

## Multi-Environment Configuration

Configure different certificates for different environments:

```groovy
def environment = System.getenv('APP_ENV') ?: 'development'

def actorTls
def hazelcastSecurity

switch (environment) {
    case 'development':
        actorTls = new RSocketTransport.TlsConfig(
            enabled: true,
            keyStorePath: '/test-certs/actor-server-keystore.jks',
            keyStorePassword: 'changeit',
            trustStorePath: '/test-certs/truststore.jks',
            trustStorePassword: 'changeit',
            developmentMode: true
        )
        
        hazelcastSecurity = HazelcastSecurityConfig.builder()
            .tlsEnabled(true)
            .developmentMode(true)
            .keyStoreClasspathResource('/test-certs/hazelcast-keystore.jks')
            .keyStorePassword('changeit')
            .trustStoreClasspathResource('/test-certs/truststore.jks')
            .trustStorePassword('changeit')
            .build()
        break
        
    case 'staging':
        actorTls = new RSocketTransport.TlsConfig(
            enabled: true,
            keyStorePath: '/certs/staging-actor-keystore.jks',
            keyStorePassword: System.getenv('STAGING_ACTOR_KEYSTORE_PASSWORD'),
            trustStorePath: '/certs/staging-truststore.jks',
            trustStorePassword: System.getenv('STAGING_TRUSTSTORE_PASSWORD')
        )
        
        hazelcastSecurity = HazelcastSecurityConfig.builder()
            .tlsEnabled(true)
            .keyStoreClasspathResource('/certs/staging-hazelcast-keystore.jks')
            .keyStorePassword(System.getenv('STAGING_HAZELCAST_PASSWORD'))
            .trustStoreClasspathResource('/certs/staging-truststore.jks')
            .trustStorePassword(System.getenv('STAGING_TRUSTSTORE_PASSWORD'))
            .build()
        break
        
    case 'production':
        // Use environment variables in production
        actorTls = RSocketTransport.TlsConfig.fromEnvironment()
        hazelcastSecurity = HazelcastSecurityConfig.fromEnvironment()
        break
}
```

## Container/Kubernetes Deployment

### Kubernetes Secrets

```yaml
# Certificate secret for both components
apiVersion: v1
kind: Secret
metadata:
  name: tls-certs
type: Opaque
data:
  actor-server-keystore.jks: <base64-encoded-keystore>
  hazelcast-keystore.jks: <base64-encoded-keystore>
  truststore.jks: <base64-encoded-truststore>

---
# Password secrets
apiVersion: v1
kind: Secret
metadata:
  name: tls-passwords
type: Opaque
stringData:
  actor-keystore-password: <your-password>
  hazelcast-keystore-password: <your-password>
  truststore-password: <your-password>
  hazelcast-cluster-password: <your-password>
  hazelcast-signing-key: <your-signing-key>
```

### Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: my-app
spec:
  template:
    spec:
      containers:
      - name: app
        image: my-app:latest
        env:
        # Remote Actor TLS
        - name: ACTOR_TLS_KEYSTORE_PATH
          value: /etc/tls/actor-server-keystore.jks
        - name: ACTOR_TLS_TRUSTSTORE_PATH
          value: /etc/tls/truststore.jks
        - name: ACTOR_KEYSTORE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: tls-passwords
              key: actor-keystore-password
        
        # Hazelcast TLS
        - name: HAZELCAST_TLS_ENABLED
          value: "true"
        - name: HAZELCAST_TLS_KEYSTORE_PATH
          value: /etc/tls/hazelcast-keystore.jks
        - name: HAZELCAST_TLS_TRUSTSTORE_PATH
          value: /etc/tls/truststore.jks
        - name: HAZELCAST_KEYSTORE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: tls-passwords
              key: hazelcast-keystore-password
        
        # Hazelcast Authentication & Signing
        - name: HAZELCAST_AUTH_ENABLED
          value: "true"
        - name: HAZELCAST_CLUSTER_USERNAME
          value: "cluster-node"
        - name: HAZELCAST_CLUSTER_PASSWORD
          valueFrom:
            secretKeyRef:
              name: tls-passwords
              key: hazelcast-cluster-password
        - name: HAZELCAST_MESSAGE_SIGNING_ENABLED
          value: "true"
        - name: HAZELCAST_MESSAGE_SIGNING_KEY
          valueFrom:
            secretKeyRef:
              name: tls-passwords
              key: hazelcast-signing-key
        
        - name: TRUSTSTORE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: tls-passwords
              key: truststore-password
              
        volumeMounts:
        - name: tls-certs
          mountPath: /etc/tls
          readOnly: true
      volumes:
      - name: tls-certs
        secret:
          secretName: tls-certs
```

## Generating Test Certificates

For development and testing, use the provided certificate generator:

```bash
cd scripts
./generate-test-certs.sh

# Or on Windows:
generate-test-certs.bat
```

### What the Script Does

The script automatically generates certificates for **all components**:

1. **Generates certificates** in `scripts/certs/`:
   - `actor-server-keystore.jks` - Actor server certificate and private key
   - `actor-client-keystore.jks` - Actor client certificate (for mTLS)
   - `hazelcast-keystore.jks` - Hazelcast node certificate
   - `truststore.jks` - Trusted CA certificates (shared by all components)
   - `ca-cert.cer` - CA certificate
   - `tls-config.properties` - Example configuration

2. **Copies to test resources** at `src/test/resources/test-certs/`:
   - Makes certificates available on classpath for tests
   - Tests can load via `/test-certs/actor-server-keystore.jks`, `/test-certs/hazelcast-keystore.jks`
   - No hardcoded filesystem paths needed

### Directory Structure After Generation

```
GroovyConcurrentUtils/
├── scripts/
│   ├── certs/                              # Local dev certificates
│   │   ├── actor-server-keystore.jks
│   │   ├── actor-client-keystore.jks
│   │   ├── hazelcast-keystore.jks
│   │   ├── truststore.jks
│   │   ├── ca-cert.cer
│   │   └── tls-config.properties
│   └── generate-test-certs.sh
│
└── src/
    └── test/
        └── resources/
            └── test-certs/                 # Classpath certificates (auto-copied)
                ├── actor-server-keystore.jks
                ├── actor-client-keystore.jks
                ├── hazelcast-keystore.jks
                └── truststore.jks
```

### Using Generated Certificates in Tests

```groovy
// Remote actors - load from classpath
def actorTls = new RSocketTransport.TlsConfig(
    enabled: true,
    keyStorePath: '/test-certs/actor-server-keystore.jks',  // Classpath resource
    keyStorePassword: 'changeit',
    trustStorePath: '/test-certs/truststore.jks',           // Classpath resource
    trustStorePassword: 'changeit',
    developmentMode: true
)

// Hazelcast - load from classpath
def hazelcastSecurity = HazelcastSecurityConfig.builder()
    .tlsEnabled(true)
    .developmentMode(true)
    .keyStoreClasspathResource('/test-certs/hazelcast-keystore.jks')
    .keyStorePassword('changeit')
    .trustStoreClasspathResource('/test-certs/truststore.jks')
    .trustStorePassword('changeit')
    .build()
```

### Certificate Locations

- **`scripts/certs/`** - For local inspection, manual use, regeneration
- **`src/test/resources/test-certs/`** - For test classpath loading
- Both directories contain the same certificates
- Script keeps them synchronized automatically

### Regenerating Certificates

Simply run the script again - it will:
- Delete old certificates
- Generate new ones in `scripts/certs/`
- Automatically copy to `src/test/resources/test-certs/`
- Update expiration dates

### Git Ignore

Both certificate directories are in `.gitignore`:
```gitignore
# Certificates (generated, not in version control)
scripts/certs/*.jks
scripts/certs/*.cer
src/test/resources/test-certs/*.jks
src/test/resources/test-certs/*.cer
```

**Rationale:** Certificates are generated locally and should not be committed to version control. Each developer generates their own.

⚠️ **WARNING**: These certificates are **self-signed** and for **testing only**. Never use in production!

## Best Practices

### Security

1. **Never hardcode passwords** - Use environment variables or secret management systems
2. **Use different certificates per component** - Actors and Hazelcast should have separate certificates
3. **Use different certificates per environment** - Dev, staging, production should have separate certs
4. **Rotate certificates regularly** - Follow your organization's rotation policy
5. **Use CA-signed certificates in production** - Not self-signed certificates
6. **Protect private keys** - Use proper file permissions (600 on Unix)
7. **Use strong passwords** - For keystore and truststore protection

### Certificate Management

1. **Expiration monitoring** - Set up alerts for certificate expiration
2. **Automation** - Automate certificate renewal (e.g., Let's Encrypt, cert-manager)
3. **Backup certificates** - Keep secure backups of certificates and keys
4. **Document certificate locations** - Maintain documentation of where certificates are stored
5. **Test certificate changes** - Always test in staging before production

### Protocol Configuration

```groovy
// Production: Only TLS 1.3
protocols: ['TLSv1.3']

// Compatibility: TLS 1.3 and 1.2
protocols: ['TLSv1.3', 'TLSv1.2']

// Never use: TLS 1.0 or 1.1 (deprecated and insecure)
```

### Unified vs Component-Specific Certificates

**Shared Truststore (Recommended):**
```
/certs/
├── actor-server-keystore.jks      # Actor-specific
├── hazelcast-keystore.jks         # Hazelcast-specific
└── truststore.jks                 # Shared by all components
```

**Separate Truststores (High Security):**
```
/certs/
├── actor-server-keystore.jks
├── actor-truststore.jks           # Only trusts actor CAs
├── hazelcast-keystore.jks
└── hazelcast-truststore.jks       # Only trusts Hazelcast CAs
```

## Troubleshooting

### Certificate Not Found

**Problem:** `FileNotFoundException: Keystore not found`

**Solutions:**
1. Verify the path is correct
2. Check if using classpath resource (must start with `/`)
3. Ensure certificate file is in resources folder or accessible filesystem path
4. Verify file permissions
5. Check `CertificateResolver` logs to see resolution attempts

### Wrong Password

**Problem:** `IOException: Keystore was tampered with, or password was incorrect`

**Solutions:**
1. Verify password is correct
2. Check environment variables are set
3. Ensure no extra whitespace in password
4. Verify keystore file is not corrupted

### SSL Handshake Failure

**Problem:** `SSLHandshakeException: Received fatal alert`

**Solutions:**
1. Verify client trusts server certificate (add to truststore)
2. Check certificate hasn't expired
3. Verify protocols match (both support TLS 1.2 or 1.3)
4. For mTLS, ensure both sides have valid certificates
5. Check certificate hostname matches connection address

### Protocol Not Supported

**Problem:** `SSLException: No appropriate protocol`

**Solutions:**
1. Check JVM supports requested TLS version
2. Update JVM if using old version
3. Verify protocol configuration matches server/client

### Resolution Debugging

Enable debug logging to see certificate resolution:

```groovy
// Add to logback.xml or log4j2.xml
<logger name="org.softwood.actor.remote.security" level="DEBUG"/>
<logger name="org.softwood.cluster" level="DEBUG"/>
```

This will show:
- Which resolution strategies were tried
- Where certificates were found
- Why certificates were rejected

## Advanced: Using CertificateResolver Programmatically

For advanced certificate resolution:

```groovy
import org.softwood.actor.remote.security.CertificateResolver

// Create resolver with development mode
def resolver = new CertificateResolver(null, true)

// Resolve keystore with multiple strategies
def keystorePath = resolver.resolve(
    null,                                  // No explicit path
    'hazelcast.tls.keystore.path',        // Try system property
    'HAZELCAST_TLS_KEYSTORE_PATH',        // Try environment variable
    '/certs/hazelcast-keystore.jks'       // Try classpath resource
)

if (keystorePath) {
    println "Keystore found: ${keystorePath}"
    
    // Validate it can be accessed
    if (resolver.validatePath(keystorePath)) {
        println "Keystore is accessible"
        
        // Open input stream
        def stream = resolver.openStream(keystorePath)
        // Use stream...
        stream.close()
    }
} else {
    println "Keystore not found"
}
```

## Summary

The unified certificate management system provides:

✅ **Single Resolution Strategy** - One `CertificateResolver` for all components  
✅ **Flexible Configuration** - Multiple ways to specify certificates  
✅ **Classpath Support** - Bundle certificates in your application  
✅ **Environment Aware** - Different certificates per environment  
✅ **Component Isolation** - Separate certificates per component  
✅ **Secure Defaults** - Encourages best practices  
✅ **Development Friendly** - Easy testing with bundled certificates  
✅ **Production Ready** - Full control for production deployments  
✅ **Unified Configuration** - Single config file for all components  

## Environment Variable Reference

### Remote Actors
- `ACTOR_TLS_KEYSTORE_PATH` - Path to actor server keystore
- `ACTOR_KEYSTORE_PASSWORD` - Actor keystore password
- `ACTOR_TLS_TRUSTSTORE_PATH` - Path to actor truststore
- `ACTOR_TRUSTSTORE_PASSWORD` - Actor truststore password

### Hazelcast Clustering
- `HAZELCAST_TLS_ENABLED` - Enable TLS (true/false)
- `HAZELCAST_TLS_KEYSTORE_PATH` - Path to Hazelcast keystore
- `HAZELCAST_KEYSTORE_PASSWORD` - Hazelcast keystore password
- `HAZELCAST_TLS_TRUSTSTORE_PATH` - Path to Hazelcast truststore
- `HAZELCAST_TRUSTSTORE_PASSWORD` - Hazelcast truststore password
- `HAZELCAST_AUTH_ENABLED` - Enable authentication (true/false)
- `HAZELCAST_CLUSTER_USERNAME` - Cluster username
- `HAZELCAST_CLUSTER_PASSWORD` - Cluster password
- `HAZELCAST_MESSAGE_SIGNING_ENABLED` - Enable message signing (true/false)
- `HAZELCAST_MESSAGE_SIGNING_KEY` - Message signing secret key
- `HAZELCAST_ALLOWED_MEMBERS` - Comma-separated list of allowed member IPs
- `HAZELCAST_BIND_ADDRESS` - Network bind address
- `HAZELCAST_PORT` - Cluster port

### Shared (Optional)
- `TRUSTSTORE_PASSWORD` - Can be used for shared truststore
- `APP_ENV` - Application environment (development/staging/production)

For more examples, see the `TLS-Usage-Examples.groovy` file in the project.
