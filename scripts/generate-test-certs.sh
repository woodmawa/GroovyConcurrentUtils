#!/bin/bash
#
# Generate self-signed SSL certificates for testing secure communication
#
# This script creates certificates for:
#   - Remote Actor Communication (RSocket with TLS)
#   - Hazelcast Clustering (Encrypted cluster communication)
#
# Generates:
#   - server-keystore.jks - Actor server certificate
#   - client-keystore.jks - Actor client certificate (mTLS)
#   - hazelcast-keystore.jks - Hazelcast node certificate
#   - truststore.jks - Shared truststore for all components
#
# Usage: ./generate-test-certs.sh [output-dir]
#

set -e

OUTPUT_DIR="${1:-./certs}"
VALIDITY_DAYS=365
KEY_SIZE=2048
PASSWORD="changeit"

echo "🔐 Generating SSL certificates for testing..."
echo "Components: Remote Actors + Hazelcast Clustering"
echo "Output directory: $OUTPUT_DIR"
echo ""

# Create output directory
mkdir -p "$OUTPUT_DIR"
cd "$OUTPUT_DIR"

# Clean up any existing certificates
echo "🧹 Cleaning up old certificates..."
rm -f server-keystore.jks client-keystore.jks hazelcast-keystore.jks truststore.jks
rm -f server-cert.cer client-cert.cer hazelcast-cert.cer tls-config.properties
echo ""

# =============================================================================
# 1. Generate Server Certificate (Actor Server)
# =============================================================================
echo "📄 Generating Actor server certificate..."

# Generate server keystore with private key and certificate
keytool -genkeypair \
    -alias server \
    -keyalg RSA \
    -keysize $KEY_SIZE \
    -validity $VALIDITY_DAYS \
    -keystore server-keystore.jks \
    -storepass "$PASSWORD" \
    -keypass "$PASSWORD" \
    -dname "CN=localhost, OU=Actors, O=Test, L=City, ST=State, C=US" \
    -ext "SAN=dns:localhost,ip:127.0.0.1"

echo "✅ Actor server keystore created: server-keystore.jks"

# Export server certificate
keytool -exportcert \
    -alias server \
    -keystore server-keystore.jks \
    -storepass "$PASSWORD" \
    -file server-cert.cer

echo "✅ Actor server certificate exported: server-cert.cer"

# =============================================================================
# 2. Generate Client Certificate (for mTLS)
# =============================================================================
echo ""
echo "📄 Generating Actor client certificate..."

# Generate client keystore with private key and certificate
keytool -genkeypair \
    -alias client \
    -keyalg RSA \
    -keysize $KEY_SIZE \
    -validity $VALIDITY_DAYS \
    -keystore client-keystore.jks \
    -storepass "$PASSWORD" \
    -keypass "$PASSWORD" \
    -dname "CN=client, OU=Actors, O=Test, L=City, ST=State, C=US"

echo "✅ Actor client keystore created: client-keystore.jks"

# Export client certificate
keytool -exportcert \
    -alias client \
    -keystore client-keystore.jks \
    -storepass "$PASSWORD" \
    -file client-cert.cer

echo "✅ Actor client certificate exported: client-cert.cer"

# =============================================================================
# 3. Generate Hazelcast Node Certificate
# =============================================================================
echo ""
echo "📄 Generating Hazelcast node certificate..."

# Generate hazelcast keystore with private key and certificate
keytool -genkeypair \
    -alias hazelcast-node \
    -keyalg RSA \
    -keysize $KEY_SIZE \
    -validity $VALIDITY_DAYS \
    -keystore hazelcast-keystore.jks \
    -storepass "$PASSWORD" \
    -keypass "$PASSWORD" \
    -dname "CN=hazelcast-node, OU=Cluster, O=Test, L=City, ST=State, C=US" \
    -ext "SAN=dns:localhost,dns:hazelcast-node,ip:127.0.0.1"

echo "✅ Hazelcast keystore created: hazelcast-keystore.jks"

# Export hazelcast certificate
keytool -exportcert \
    -alias hazelcast-node \
    -keystore hazelcast-keystore.jks \
    -storepass "$PASSWORD" \
    -file hazelcast-cert.cer

echo "✅ Hazelcast certificate exported: hazelcast-cert.cer"

# =============================================================================
# 4. Create Shared Truststore
# =============================================================================
echo ""
echo "📄 Creating shared truststore (for all components)..."

# Create truststore and import server certificate
keytool -importcert \
    -noprompt \
    -alias server \
    -file server-cert.cer \
    -keystore truststore.jks \
    -storepass "$PASSWORD"

echo "✅ Actor server certificate imported to truststore"

# Import client certificate to truststore (for mTLS)
keytool -importcert \
    -noprompt \
    -alias client \
    -file client-cert.cer \
    -keystore truststore.jks \
    -storepass "$PASSWORD"

echo "✅ Actor client certificate imported to truststore"

# Import hazelcast certificate to truststore
keytool -importcert \
    -noprompt \
    -alias hazelcast-node \
    -file hazelcast-cert.cer \
    -keystore truststore.jks \
    -storepass "$PASSWORD"

echo "✅ Hazelcast certificate imported to truststore"

# =============================================================================
# 5. Verify Certificates
# =============================================================================
echo ""
echo "🔍 Verifying certificates..."

echo ""
echo "Actor server keystore contents:"
keytool -list -keystore server-keystore.jks -storepass "$PASSWORD" | grep "Alias name:"

echo ""
echo "Actor client keystore contents:"
keytool -list -keystore client-keystore.jks -storepass "$PASSWORD" | grep "Alias name:"

echo ""
echo "Hazelcast keystore contents:"
keytool -list -keystore hazelcast-keystore.jks -storepass "$PASSWORD" | grep "Alias name:"

echo ""
echo "Shared truststore contents:"
keytool -list -keystore truststore.jks -storepass "$PASSWORD" | grep "Alias name:"

# =============================================================================
# 6. Generate Unified Configuration
# =============================================================================
echo ""
echo "📝 Generating unified TLS configuration..."

cat > tls-config.properties << EOF
# Unified TLS Configuration for All Components
# Generated on $(date)

# =============================================================================
# Remote Actor Communication
# =============================================================================

# Actor Server configuration
actor.remote.rsocket.tls.enabled=true
actor.remote.rsocket.tls.keyStore=$(pwd)/server-keystore.jks
actor.remote.rsocket.tls.keyStorePassword=$PASSWORD
actor.remote.rsocket.tls.trustStore=$(pwd)/truststore.jks
actor.remote.rsocket.tls.trustStorePassword=$PASSWORD

# Actor Client configuration (for mTLS)
actor.remote.client.tls.enabled=true
actor.remote.client.tls.keyStore=$(pwd)/client-keystore.jks
actor.remote.client.tls.keyStorePassword=$PASSWORD
actor.remote.client.tls.trustStore=$(pwd)/truststore.jks
actor.remote.client.tls.trustStorePassword=$PASSWORD

# Actor Protocols (only modern, secure protocols)
actor.remote.tls.protocols=TLSv1.3,TLSv1.2

# =============================================================================
# Hazelcast Clustering
# =============================================================================

# Hazelcast TLS configuration
hazelcast.tls.enabled=true
hazelcast.tls.keyStore=$(pwd)/hazelcast-keystore.jks
hazelcast.tls.keyStorePassword=$PASSWORD
hazelcast.tls.trustStore=$(pwd)/truststore.jks
hazelcast.tls.trustStorePassword=$PASSWORD

# Hazelcast Protocols (production should use only TLSv1.3)
hazelcast.tls.protocols=TLSv1.3,TLSv1.2

# Hazelcast Authentication (example - change in production!)
hazelcast.auth.enabled=false
hazelcast.auth.username=test-cluster-node
hazelcast.auth.password=changeit

# Hazelcast Message Signing (example - change in production!)
hazelcast.message.signing.enabled=false
hazelcast.message.signing.key=test-signing-key-change-in-production
hazelcast.message.signing.algorithm=HmacSHA256

# =============================================================================
# Shared Settings
# =============================================================================

# Truststore (shared by all components)
truststore.path=$(pwd)/truststore.jks
truststore.password=$PASSWORD
EOF

echo "✅ Unified configuration generated: tls-config.properties"

# =============================================================================
# 7. Copy to Test Resources (for classpath loading in tests)
# =============================================================================
echo ""
echo "📋 Copying certificates to test resources..."

# Go back to scripts directory
cd ..

TEST_RESOURCES_DIR="../src/test/resources/test-certs"

mkdir -p "$TEST_RESOURCES_DIR"

cp certs/server-keystore.jks "$TEST_RESOURCES_DIR/"
cp certs/client-keystore.jks "$TEST_RESOURCES_DIR/"
cp certs/hazelcast-keystore.jks "$TEST_RESOURCES_DIR/"
cp certs/truststore.jks "$TEST_RESOURCES_DIR/"

if [ $? -eq 0 ]; then
    echo "✅ Certificates copied to test resources"
    echo "   Location: src/test/resources/test-certs/"
    echo "   Tests can now load certs from classpath:"
    echo "     - /test-certs/server-keystore.jks (Actor server)"
    echo "     - /test-certs/client-keystore.jks (Actor client)"
    echo "     - /test-certs/hazelcast-keystore.jks (Hazelcast)"
    echo "     - /test-certs/truststore.jks (Shared truststore)"
else
    echo "⚠️  Failed to copy to test resources - tests may need manual certificate setup"
fi

# Go back to certs directory for summary
cd certs

# =============================================================================
# Summary
# =============================================================================
echo ""
echo "✅ Certificate generation complete!"
echo ""
echo "Generated files in scripts/certs/:"
echo "  📄 server-keystore.jks      - Actor server private key and certificate"
echo "  📄 client-keystore.jks      - Actor client private key and certificate (mTLS)"
echo "  📄 hazelcast-keystore.jks   - Hazelcast node private key and certificate"
echo "  📄 truststore.jks           - Shared truststore for all components"
echo "  📄 server-cert.cer          - Actor server certificate (for inspection)"
echo "  📄 client-cert.cer          - Actor client certificate (for inspection)"
echo "  📄 hazelcast-cert.cer       - Hazelcast certificate (for inspection)"
echo "  📄 tls-config.properties    - Unified configuration file"
echo ""
echo "Also copied to src/test/resources/test-certs/ for test classpath loading"
echo ""
echo "⚠️  WARNING: These are self-signed certificates for TESTING ONLY!"
echo "    Do NOT use in production. Generate proper CA-signed certificates."
echo ""
echo "Password for all keystores: $PASSWORD"
echo ""
echo "Components configured:"
echo "  ✅ Remote Actor Communication (RSocket with TLS)"
echo "  ✅ Hazelcast Clustering (Encrypted cluster communication)"
echo ""
echo "To use in your application:"
echo "  1. Development/Testing:"
echo "     - Certs are in classpath: '/test-certs/server-keystore.jks'"
echo "     - Enable development mode in security config"
echo ""
echo "  2. Production:"
echo "     - Generate proper CA-signed certificates"
echo "     - Configure via environment variables:"
echo "       export ACTOR_TLS_KEYSTORE_PATH=/etc/certs/server-keystore.jks"
echo "       export HAZELCAST_TLS_KEYSTORE_PATH=/etc/certs/hazelcast-keystore.jks"
echo ""
echo "  3. Configuration:"
echo "     - See tls-config.properties for examples"
echo "     - See docs/Certificate_Management_Guide.md for complete documentation"
echo ""
