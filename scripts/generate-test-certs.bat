@echo off
REM Generate SSL certificates for testing secure communication
REM Auto-detect keytool location

setlocal enabledelayedexpansion

REM Find Java home
for /f "tokens=*" %%i in ('where java 2^>nul') do set JAVA_EXE=%%i
if "%JAVA_EXE%"=="" (
    echo ❌ Java not found in PATH
    echo Please install JDK and ensure java.exe is in PATH
    pause
    exit /b 1
)

REM Extract Java bin directory
for %%i in ("%JAVA_EXE%") do set JAVA_BIN=%%~dpi
set KEYTOOL=%JAVA_BIN%keytool.exe

echo Found keytool at: %KEYTOOL%
echo.

REM Check if keytool exists
if not exist "%KEYTOOL%" (
    echo ❌ keytool.exe not found at %KEYTOOL%
    pause
    exit /b 1
)

set OUTPUT_DIR=%1
if "%OUTPUT_DIR%"=="" set OUTPUT_DIR=.\certs

set VALIDITY_DAYS=365
set KEY_SIZE=2048
set PASSWORD=changeit

echo 🔐 Generating SSL certificates for testing...
echo Components: Remote Actors + Hazelcast Clustering
echo Output directory: %OUTPUT_DIR%
echo.

REM Create output directory
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"
cd /d "%OUTPUT_DIR%"

REM Clean up any existing certificates
echo 🧹 Cleaning up old certificates...
if exist server-keystore.jks del /F /Q server-keystore.jks
if exist client-keystore.jks del /F /Q client-keystore.jks
if exist hazelcast-keystore.jks del /F /Q hazelcast-keystore.jks
if exist truststore.jks del /F /Q truststore.jks
if exist server-cert.cer del /F /Q server-cert.cer
if exist client-cert.cer del /F /Q client-cert.cer
if exist hazelcast-cert.cer del /F /Q hazelcast-cert.cer
if exist tls-config.properties del /F /Q tls-config.properties
echo.

REM =============================================================================
REM 1. Generate Server Certificate (Actor Server)
REM =============================================================================
echo 📄 Generating Actor server certificate...

"%KEYTOOL%" -genkeypair ^
    -alias server ^
    -keyalg RSA ^
    -keysize %KEY_SIZE% ^
    -validity %VALIDITY_DAYS% ^
    -keystore server-keystore.jks ^
    -storepass "%PASSWORD%" ^
    -keypass "%PASSWORD%" ^
    -dname "CN=localhost, OU=Actors, O=Test, L=City, ST=State, C=US" ^
    -ext "SAN=dns:localhost,ip:127.0.0.1"

if %ERRORLEVEL% NEQ 0 (
    echo ❌ Failed to generate Actor server certificate
    pause
    exit /b 1
)

echo ✅ Actor server keystore created: server-keystore.jks

"%KEYTOOL%" -exportcert ^
    -alias server ^
    -keystore server-keystore.jks ^
    -storepass "%PASSWORD%" ^
    -file server-cert.cer

echo ✅ Actor server certificate exported: server-cert.cer

REM =============================================================================
REM 2. Generate Client Certificate (for mTLS)
REM =============================================================================
echo.
echo 📄 Generating Actor client certificate...

"%KEYTOOL%" -genkeypair ^
    -alias client ^
    -keyalg RSA ^
    -keysize %KEY_SIZE% ^
    -validity %VALIDITY_DAYS% ^
    -keystore client-keystore.jks ^
    -storepass "%PASSWORD%" ^
    -keypass "%PASSWORD%" ^
    -dname "CN=client, OU=Actors, O=Test, L=City, ST=State, C=US"

if %ERRORLEVEL% NEQ 0 (
    echo ❌ Failed to generate Actor client certificate
    pause
    exit /b 1
)

echo ✅ Actor client keystore created: client-keystore.jks

"%KEYTOOL%" -exportcert ^
    -alias client ^
    -keystore client-keystore.jks ^
    -storepass "%PASSWORD%" ^
    -file client-cert.cer

echo ✅ Actor client certificate exported: client-cert.cer

REM =============================================================================
REM 3. Generate Hazelcast Node Certificate
REM =============================================================================
echo.
echo 📄 Generating Hazelcast node certificate...

"%KEYTOOL%" -genkeypair ^
    -alias hazelcast-node ^
    -keyalg RSA ^
    -keysize %KEY_SIZE% ^
    -validity %VALIDITY_DAYS% ^
    -keystore hazelcast-keystore.jks ^
    -storepass "%PASSWORD%" ^
    -keypass "%PASSWORD%" ^
    -dname "CN=hazelcast-node, OU=Cluster, O=Test, L=City, ST=State, C=US" ^
    -ext "SAN=dns:localhost,dns:hazelcast-node,ip:127.0.0.1"

if %ERRORLEVEL% NEQ 0 (
    echo ❌ Failed to generate Hazelcast certificate
    pause
    exit /b 1
)

echo ✅ Hazelcast keystore created: hazelcast-keystore.jks

"%KEYTOOL%" -exportcert ^
    -alias hazelcast-node ^
    -keystore hazelcast-keystore.jks ^
    -storepass "%PASSWORD%" ^
    -file hazelcast-cert.cer

echo ✅ Hazelcast certificate exported: hazelcast-cert.cer

REM =============================================================================
REM 4. Create Shared Truststore
REM =============================================================================
echo.
echo 📄 Creating shared truststore (for all components)...

"%KEYTOOL%" -importcert ^
    -noprompt ^
    -alias server ^
    -file server-cert.cer ^
    -keystore truststore.jks ^
    -storepass "%PASSWORD%"

echo ✅ Actor server certificate imported to truststore

"%KEYTOOL%" -importcert ^
    -noprompt ^
    -alias client ^
    -file client-cert.cer ^
    -keystore truststore.jks ^
    -storepass "%PASSWORD%"

echo ✅ Actor client certificate imported to truststore

"%KEYTOOL%" -importcert ^
    -noprompt ^
    -alias hazelcast-node ^
    -file hazelcast-cert.cer ^
    -keystore truststore.jks ^
    -storepass "%PASSWORD%"

echo ✅ Hazelcast certificate imported to truststore

REM =============================================================================
REM 5. Verify Certificates
REM =============================================================================
echo.
echo 🔍 Verifying certificates...

echo.
echo Actor server keystore contents:
"%KEYTOOL%" -list -keystore server-keystore.jks -storepass "%PASSWORD%" | findstr "Alias"

echo.
echo Actor client keystore contents:
"%KEYTOOL%" -list -keystore client-keystore.jks -storepass "%PASSWORD%" | findstr "Alias"

echo.
echo Hazelcast keystore contents:
"%KEYTOOL%" -list -keystore hazelcast-keystore.jks -storepass "%PASSWORD%" | findstr "Alias"

echo.
echo Shared truststore contents:
"%KEYTOOL%" -list -keystore truststore.jks -storepass "%PASSWORD%" | findstr "Alias"

REM =============================================================================
REM 6. Generate Unified Configuration
REM =============================================================================
echo.
echo 📝 Generating unified TLS configuration...

set CURRENT_DIR=%CD%
set CURRENT_DIR=%CURRENT_DIR:\=/%

(
echo # Unified TLS Configuration for All Components
echo # Generated on %date% %time%
echo.
echo # =============================================================================
echo # Remote Actor Communication
echo # =============================================================================
echo.
echo # Actor Server configuration
echo actor.remote.rsocket.tls.enabled=true
echo actor.remote.rsocket.tls.keyStore=%CURRENT_DIR%/server-keystore.jks
echo actor.remote.rsocket.tls.keyStorePassword=%PASSWORD%
echo actor.remote.rsocket.tls.trustStore=%CURRENT_DIR%/truststore.jks
echo actor.remote.rsocket.tls.trustStorePassword=%PASSWORD%
echo.
echo # Actor Client configuration (for mTLS^)
echo actor.remote.client.tls.enabled=true
echo actor.remote.client.tls.keyStore=%CURRENT_DIR%/client-keystore.jks
echo actor.remote.client.tls.keyStorePassword=%PASSWORD%
echo actor.remote.client.tls.trustStore=%CURRENT_DIR%/truststore.jks
echo actor.remote.client.tls.trustStorePassword=%PASSWORD%
echo.
echo # Actor Protocols (only modern, secure protocols^)
echo actor.remote.tls.protocols=TLSv1.3,TLSv1.2
echo.
echo # =============================================================================
echo # Hazelcast Clustering
echo # =============================================================================
echo.
echo # Hazelcast TLS configuration
echo hazelcast.tls.enabled=true
echo hazelcast.tls.keyStore=%CURRENT_DIR%/hazelcast-keystore.jks
echo hazelcast.tls.keyStorePassword=%PASSWORD%
echo hazelcast.tls.trustStore=%CURRENT_DIR%/truststore.jks
echo hazelcast.tls.trustStorePassword=%PASSWORD%
echo.
echo # Hazelcast Protocols (production should use only TLSv1.3^)
echo hazelcast.tls.protocols=TLSv1.3,TLSv1.2
echo.
echo # Hazelcast Authentication (example - change in production!^)
echo hazelcast.auth.enabled=false
echo hazelcast.auth.username=test-cluster-node
echo hazelcast.auth.password=changeit
echo.
echo # Hazelcast Message Signing (example - change in production!^)
echo hazelcast.message.signing.enabled=false
echo hazelcast.message.signing.key=test-signing-key-change-in-production
echo hazelcast.message.signing.algorithm=HmacSHA256
echo.
echo # =============================================================================
echo # Shared Settings
echo # =============================================================================
echo.
echo # Truststore (shared by all components^)
echo truststore.path=%CURRENT_DIR%/truststore.jks
echo truststore.password=%PASSWORD%
) > tls-config.properties

echo ✅ Unified configuration generated: tls-config.properties

REM =============================================================================
REM 7. Copy to Test Resources (for classpath loading in tests)
REM =============================================================================
echo.
echo 📋 Copying certificates to test resources...

REM Go back to scripts directory
cd ..

set TEST_RESOURCES_DIR=..\src\test\resources\test-certs

if not exist "%TEST_RESOURCES_DIR%" mkdir "%TEST_RESOURCES_DIR%"

copy /Y certs\server-keystore.jks "%TEST_RESOURCES_DIR%\server-keystore.jks" >nul
copy /Y certs\client-keystore.jks "%TEST_RESOURCES_DIR%\client-keystore.jks" >nul
copy /Y certs\hazelcast-keystore.jks "%TEST_RESOURCES_DIR%\hazelcast-keystore.jks" >nul
copy /Y certs\truststore.jks "%TEST_RESOURCES_DIR%\truststore.jks" >nul

if %ERRORLEVEL% EQU 0 (
    echo ✅ Certificates copied to test resources
    echo    Location: src\test\resources\test-certs\
    echo    Tests can now load certs from classpath:
    echo      - /test-certs/server-keystore.jks (Actor server^)
    echo      - /test-certs/client-keystore.jks (Actor client^)
    echo      - /test-certs/hazelcast-keystore.jks (Hazelcast^)
    echo      - /test-certs/truststore.jks (Shared truststore^)
) else (
    echo ⚠️  Failed to copy to test resources
    echo    Error level: %ERRORLEVEL%
)

REM Go back to certs directory for summary
cd certs

REM =============================================================================
REM Summary
REM =============================================================================
echo.
echo ✅ Certificate generation complete!
echo.
echo Generated files in scripts/certs/:
echo   📄 server-keystore.jks      - Actor server private key and certificate
echo   📄 client-keystore.jks      - Actor client private key and certificate (mTLS^)
echo   📄 hazelcast-keystore.jks   - Hazelcast node private key and certificate
echo   📄 truststore.jks           - Shared truststore for all components
echo   📄 server-cert.cer          - Actor server certificate (for inspection^)
echo   📄 client-cert.cer          - Actor client certificate (for inspection^)
echo   📄 hazelcast-cert.cer       - Hazelcast certificate (for inspection^)
echo   📄 tls-config.properties    - Unified configuration file
echo.
echo Also copied to src/test/resources/test-certs/ for test classpath loading
echo.
echo ⚠️  WARNING: These are self-signed certificates for TESTING ONLY!
echo     Do NOT use in production. Generate proper CA-signed certificates.
echo.
echo Password for all keystores: %PASSWORD%
echo.
echo Components configured:
echo   ✅ Remote Actor Communication (RSocket with TLS^)
echo   ✅ Hazelcast Clustering (Encrypted cluster communication^)
echo.
echo To use in your application:
echo   1. Development/Testing:
echo      - Certs are in classpath: '/test-certs/server-keystore.jks'
echo      - Enable development mode in security config
echo.
echo   2. Production:
echo      - Generate proper CA-signed certificates
echo      - Configure via environment variables:
echo        set ACTOR_TLS_KEYSTORE_PATH=C:\certs\server-keystore.jks
echo        set HAZELCAST_TLS_KEYSTORE_PATH=C:\certs\hazelcast-keystore.jks
echo.
echo   3. Configuration:
echo      - See tls-config.properties for examples
echo      - See docs\Certificate_Management_Guide.md for complete documentation
echo.

pause
endlocal
