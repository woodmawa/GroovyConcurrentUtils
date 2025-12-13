#!/bin/bash
#
# Generate self-signed SSL certificates for testing Actor remote communication
#
# This script creates:
#   - Server keystore with server certificate
#   - Client keystore with client certificate  
#   - Truststore with both CA certificates
#
# Usage: ./generate-test-certs.sh [output-dir]
#

set -e

OUTPUT_DIR="${1:-./certs}"
VALIDITY_DAYS=365
KEY_SIZE=2048
PASSWORD="changeit"

echo "🔐 Generating SSL certificates for testing..."
echo "Output directory: $OUTPUT_DIR"
echo ""

# Create output directory
mkdir -p "$OUTPUT_DIR"
cd "$OUTPUT_DIR"

# =============================================================================
# 1. Generate Server Certificate
# =============================================================================
echo "📄 Generating server certificate..."

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

echo "✅ Server keystore created: server-keystore.jks"

# Export server certificate
keytool -exportcert \
    -alias server \
    -keystore server-keystore.jks \
    -storepass "$PASSWORD" \
    -file server-cert.cer

echo "✅ Server certificate exported: server-cert.cer"

# =============================================================================
# 2. Generate Client Certificate (for mTLS)
# =============================================================================
echo ""
echo "📄 Generating client certificate..."

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

echo "✅ Client keystore created: client-keystore.jks"

# Export client certificate
keytool -exportcert \
    -alias client \
    -keystore client-keystore.jks \
    -storepass "$PASSWORD" \
    -file client-cert.cer

echo "✅ Client certificate exported: client-cert.cer"

# =============================================================================
# 3. Create Truststore
# =============================================================================
echo ""
echo "📄 Creating truststore..."

# Create truststore and import server certificate
keytool -importcert \
    -noprompt \
    -alias server \
    -file server-cert.cer \
    -keystore truststore.jks \
    -storepass "$PASSWORD"

echo "✅ Server certificate imported to truststore"

# Import client certificate to truststore (for mTLS)
keytool -importcert \
    -noprompt \
    -alias client \
    -file client-cert.cer \
    -keystore truststore.jks \
    -storepass "$PASSWORD"

echo "✅ Client certificate imported to truststore"

# =============================================================================
# 4. Verify Certificates
# =============================================================================
echo ""
echo "🔍 Verifying certificates..."

echo ""
echo "Server keystore contents:"
keytool -list -keystore server-keystore.jks -storepass "$PASSWORD" | grep "Alias name:"

echo ""
echo "Client keystore contents:"
keytool -list -keystore client-keystore.jks -storepass "$PASSWORD" | grep "Alias name:"

echo ""
echo "Truststore contents:"
keytool -list -keystore truststore.jks -storepass "$PASSWORD" | grep "Alias name:"

# =============================================================================
# 5. Generate Configuration
# =============================================================================
echo ""
echo "📝 Generating configuration..."

cat > tls-config.properties << EOF
# TLS Configuration for Actor Remote Communication
# Generated on $(date)

# Server configuration
actor.remote.rsocket.tls.enabled=true
actor.remote.rsocket.tls.keyStore=$(pwd)/server-keystore.jks
actor.remote.rsocket.tls.keyStorePassword=$PASSWORD
actor.remote.rsocket.tls.trustStore=$(pwd)/truststore.jks
actor.remote.rsocket.tls.trustStorePassword=$PASSWORD

# Client configuration
actor.remote.client.tls.enabled=true
actor.remote.client.tls.keyStore=$(pwd)/client-keystore.jks
actor.remote.client.tls.keyStorePassword=$PASSWORD
actor.remote.client.tls.trustStore=$(pwd)/truststore.jks
actor.remote.client.tls.trustStorePassword=$PASSWORD

# Protocols (only modern, secure protocols)
actor.remote.tls.protocols=TLSv1.3,TLSv1.2
EOF

echo "✅ Configuration generated: tls-config.properties"

# =============================================================================
# Summary
# =============================================================================
echo ""
echo "✅ Certificate generation complete!"
echo ""
echo "Generated files:"
echo "  📄 server-keystore.jks    - Server private key and certificate"
echo "  📄 client-keystore.jks    - Client private key and certificate (mTLS)"
echo "  📄 truststore.jks         - Trusted CA certificates"
echo "  📄 server-cert.cer        - Server certificate (for inspection)"
echo "  📄 client-cert.cer        - Client certificate (for inspection)"
echo "  📄 tls-config.properties  - Configuration file"
echo ""
echo "⚠️  WARNING: These are self-signed certificates for TESTING ONLY!"
echo "    Do NOT use in production. Generate proper CA-signed certificates."
echo ""
echo "Password for all keystores: $PASSWORD"
echo ""
echo "To use in your application:"
echo "  1. Copy certs to a secure location"
echo "  2. Update config.groovy with paths and passwords"
echo "  3. Enable TLS: actor.remote.rsocket.tls.enabled = true"
echo ""
