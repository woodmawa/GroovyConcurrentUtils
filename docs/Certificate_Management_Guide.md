# Certificate Management for Actor Remoting

## Overview

GroovyConcurrentUtils supports secure TLS/SSL-encrypted actor remoting with flexible certificate configuration. This guide explains how to configure certificates for different environments and deployment scenarios.

## Quick Start

### Development (Test Certificates)

For development and testing, use classpath resources with test certificates:

```groovy
def tlsConfig = new RSocketTransport.TlsConfig(
    enabled: true,
    keyStorePath: '/test-certs/server-keystore.jks',
    keyStorePassword: 'changeit',
    trustStorePath: '/test-certs/truststore.jks',
    trustStorePassword: 'changeit'
)
```

### Production (Your Certificates)

Place your certificates in `src/main/resources/certs/` and load them:

```groovy
def tlsConfig = new RSocketTransport.TlsConfig(
    enabled: true,
    keyStorePath: '/certs/keystore.jks',
    keyStorePassword: System.getenv('KEYSTORE_PASSWORD'),
    trustStorePath: '/certs/truststore.jks',
    trustStorePassword: System.getenv('TRUSTSTORE_PASSWORD')
)
```

## Certificate Resolution Strategy

The library uses a priority-based resolution strategy to find certificates:

1. **Explicit Path** (highest priority) - Direct path provided in code
2. **System Property** - Java system property (`-Dactor.tls.keystore.path=...`)
3. **Environment Variable** - OS environment variable (`ACTOR_TLS_KEYSTORE_PATH`)
4. **Config File** - Groovy ConfigSlurper configuration
5. **Classpath Resource** - Resource in JAR/classpath (`/certs/keystore.jks`)
6. **Test Certificates** (dev mode only) - Bundled test certificates

## Configuration Methods

### 1. Classpath Resources (Recommended)

Place certificates in your project's resources:

```
my-project/
├── src/main/resources/
│   └── certs/
│       ├── keystore.jks
│       └── truststore.jks
```

Configure:
```groovy
def tlsConfig = new RSocketTransport.TlsConfig(
    enabled: true,
    keyStorePath: '/certs/keystore.jks',      // Classpath resource
    keyStorePassword: System.getenv('KEYSTORE_PASSWORD'),
    trustStorePath: '/certs/truststore.jks',
    trustStorePassword: System.getenv('TRUSTSTORE_PASSWORD')
)
```

**Advantages:**
- Certificates bundled with application
- Works in containers and cloud deployments
- Simple deployment

### 2. System Properties

Configure via command line:
```bash
java -Dactor.tls.keystore.path=/etc/myapp/certs/keystore.jks \
     -Dactor.tls.truststore.path=/etc/myapp/certs/truststore.jks \
     -jar myapp.jar
```

**Advantages:**
- Override at runtime
- No code changes needed
- Good for testing different certificates

### 3. Environment Variables

Configure via environment:
```bash
export ACTOR_TLS_KEYSTORE_PATH=/etc/myapp/certs/keystore.jks
export ACTOR_TLS_TRUSTSTORE_PATH=/etc/myapp/certs/truststore.jks
```

**Advantages:**
- Standard 12-factor app approach
- Container/Kubernetes friendly
- Secure secret management

### 4. Explicit Paths

Provide paths directly in code:
```groovy
def tlsConfig = new RSocketTransport.TlsConfig(
    enabled: true,
    keyStorePath: '/absolute/path/to/keystore.jks',
    keyStorePassword: 'mypassword',
    trustStorePath: '/absolute/path/to/truststore.jks',
    trustStorePassword: 'mypassword'
)
```

**Advantages:**
- Full control
- Simple for single environment
- Easy debugging

### 5. Configuration Files

Create `tls-config.groovy`:
```groovy
actor {
    tls {
        enabled = true
        keystore {
            path = System.getenv('TLS_KEYSTORE_PATH') ?: '/etc/myapp/certs/keystore.jks'
            password = System.getenv('TLS_KEYSTORE_PASSWORD')
        }
        truststore {
            path = System.getenv('TLS_TRUSTSTORE_PATH') ?: '/etc/myapp/certs/truststore.jks'
            password = System.getenv('TLS_TRUSTSTORE_PASSWORD')
        }
        protocols = ['TLSv1.3', 'TLSv1.2']
    }
}
```

Load and use:
```groovy
def config = new ConfigSlurper().parse(new File('tls-config.groovy').toURI().toURL())

def tlsConfig = new RSocketTransport.TlsConfig(
    enabled: config.actor.tls.enabled,
    keyStorePath: config.actor.tls.keystore.path,
    keyStorePassword: config.actor.tls.keystore.password,
    trustStorePath: config.actor.tls.truststore.path,
    trustStorePassword: config.actor.tls.truststore.password,
    protocols: config.actor.tls.protocols
)
```

## Multi-Environment Configuration

Different certificates for different environments:

```groovy
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
```

## Client-Only vs Server Configuration

### Server Configuration (Has Certificate)

```groovy
def serverTlsConfig = new RSocketTransport.TlsConfig(
    enabled: true,
    keyStorePath: '/certs/server-keystore.jks',      // Server certificate
    keyStorePassword: System.getenv('SERVER_KEYSTORE_PASSWORD'),
    trustStorePath: '/certs/truststore.jks',         // Trusted clients (for mTLS)
    trustStorePassword: System.getenv('TRUSTSTORE_PASSWORD')
)
```

### Client Configuration (Connects to Server)

```groovy
def clientTlsConfig = new RSocketTransport.TlsConfig(
    enabled: true,
    // No keyStorePath needed for basic TLS
    trustStorePath: '/certs/truststore.jks',         // Trust server certificate
    trustStorePassword: System.getenv('TRUSTSTORE_PASSWORD')
)
```

### Mutual TLS (mTLS) Client

```groovy
def clientTlsConfig = new RSocketTransport.TlsConfig(
    enabled: true,
    keyStorePath: '/certs/client-keystore.jks',      // Client certificate
    keyStorePassword: System.getenv('CLIENT_KEYSTORE_PASSWORD'),
    trustStorePath: '/certs/truststore.jks',         // Trust server certificate
    trustStorePassword: System.getenv('TRUSTSTORE_PASSWORD')
)
```

## Container/Kubernetes Deployment

### Kubernetes Secrets

```yaml
# Certificate secret
apiVersion: v1
kind: Secret
metadata:
  name: actor-tls-certs
type: Opaque
data:
  keystore.jks: <base64-encoded-keystore>
  truststore.jks: <base64-encoded-truststore>

---
# Password secret
apiVersion: v1
kind: Secret
metadata:
  name: tls-passwords
type: Opaque
stringData:
  keystore-password: <your-password>
  truststore-password: <your-password>
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
        - name: ACTOR_TLS_KEYSTORE_PATH
          value: /etc/tls/keystore.jks
        - name: ACTOR_TLS_TRUSTSTORE_PATH
          value: /etc/tls/truststore.jks
        - name: KEYSTORE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: tls-passwords
              key: keystore-password
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
          secretName: actor-tls-certs
```

## Best Practices

### Security

1. **Never hardcode passwords** - Use environment variables or secret management systems
2. **Use different certificates per environment** - Dev, staging, production should have separate certs
3. **Rotate certificates regularly** - Follow your organization's rotation policy
4. **Use CA-signed certificates in production** - Not self-signed certificates
5. **Protect private keys** - Use proper file permissions (600 on Unix)
6. **Use strong passwords** - For keystore and truststore protection

### Certificate Management

1. **Expiration monitoring** - Set up alerts for certificate expiration
2. **Automation** - Automate certificate renewal (e.g., Let's Encrypt)
3. **Backup certificates** - Keep secure backups of certificates and keys
4. **Document certificate locations** - Maintain documentation of where certificates are stored
5. **Test certificate changes** - Always test in staging before production

### Protocol Configuration

```groovy
// Production: Only TLS 1.3
protocols: ['TLSv1.3']

// Compatibility: TLS 1.3 and 1.2
protocols: ['TLSv1.3', 'TLSv1.2']

// Never use: TLS 1.0 or 1.1 (deprecated)
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

The script automatically:
1. **Generates certificates** in `scripts/certs/`:
   - `server-keystore.jks` - Server certificate and private key
   - `client-keystore.jks` - Client certificate (for mTLS)
   - `truststore.jks` - Trusted CA certificates
   - `tls-config.properties` - Example configuration

2. **Copies to test resources** at `src/test/resources/test-certs/`:
   - Makes certificates available on classpath for tests
   - Tests can load via `/test-certs/server-keystore.jks`
   - No hardcoded filesystem paths needed

### Directory Structure After Generation

```
GroovyConcurrentUtils/
├── scripts/
│   ├── certs/                      # Local dev certificates
│   │   ├── server-keystore.jks
│   │   ├── client-keystore.jks
│   │   ├── truststore.jks
│   │   ├── server-cert.cer
│   │   ├── client-cert.cer
│   │   └── tls-config.properties
│   └── generate-test-certs.bat
│
└── src/
    └── test/
        └── resources/
            └── test-certs/         # Classpath certificates (auto-copied)
                ├── server-keystore.jks
                ├── client-keystore.jks
                └── truststore.jks
```

### Using Generated Certificates in Tests

```groovy
// Tests load from classpath automatically
def tlsConfig = new RSocketTransport.TlsConfig(
    enabled: true,
    keyStorePath: '/test-certs/server-keystore.jks',  // Classpath resource
    keyStorePassword: 'changeit',
    trustStorePath: '/test-certs/truststore.jks',     // Classpath resource
    trustStorePassword: 'changeit',
    developmentMode: true
)
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

## Troubleshooting

### Certificate Not Found

**Problem:** `FileNotFoundException: Keystore not found`

**Solutions:**
1. Verify the path is correct
2. Check if using classpath resource (must start with `/`)
3. Ensure certificate file is in resources folder
4. Verify file permissions

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

### Protocol Not Supported

**Problem:** `SSLException: No appropriate protocol`

**Solutions:**
1. Check JVM supports requested TLS version
2. Update JVM if using old version
3. Verify protocol configuration matches server/client

## Advanced: Using CertificateResolver

For advanced certificate resolution:

```groovy
import org.softwood.actor.remote.security.CertificateResolver
import org.softwood.actor.remote.security.TlsContextBuilder

// Create resolver
def resolver = new CertificateResolver(null, false)

// Resolve keystore with multiple strategies
def keystorePath = resolver.resolve(
    null,                              // No explicit path
    'actor.tls.keystore.path',        // Try system property
    'ACTOR_TLS_KEYSTORE_PATH',        // Try environment variable
    '/certs/keystore.jks'             // Try classpath resource
)

// Use with TlsContextBuilder
def sslContext = TlsContextBuilder.builder()
    .useResolver(true)
    .keyStore(keystorePath, System.getenv('KEYSTORE_PASSWORD'))
    .trustStore('/certs/truststore.jks', System.getenv('TRUSTSTORE_PASSWORD'))
    .protocols(['TLSv1.3', 'TLSv1.2'])
    .build()
```

## Summary

The certificate management system provides:

✅ **Flexible Configuration** - Multiple ways to specify certificates  
✅ **Classpath Support** - Bundle certificates in your application  
✅ **Environment Aware** - Different certificates per environment  
✅ **Secure Defaults** - Encourages best practices  
✅ **Development Friendly** - Easy testing with bundled certificates  
✅ **Production Ready** - Full control for production deployments  

For more examples, see the `TLS-Usage-Examples.groovy` file in the project.
