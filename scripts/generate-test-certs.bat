@echo off
REM Generate SSL certificates - Auto-detect keytool location

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
echo Output directory: %OUTPUT_DIR%
echo.

REM Create output directory
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"
cd /d "%OUTPUT_DIR%"

REM Clean up any existing certificates
echo 🧹 Cleaning up old certificates...
if exist server-keystore.jks del /F /Q server-keystore.jks
if exist client-keystore.jks del /F /Q client-keystore.jks
if exist truststore.jks del /F /Q truststore.jks
if exist server-cert.cer del /F /Q server-cert.cer
if exist client-cert.cer del /F /Q client-cert.cer
if exist tls-config.properties del /F /Q tls-config.properties
echo.

REM =============================================================================
REM 1. Generate Server Certificate
REM =============================================================================
echo 📄 Generating server certificate...

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
    echo ❌ Failed to generate server certificate
    pause
    exit /b 1
)

echo ✅ Server keystore created: server-keystore.jks

"%KEYTOOL%" -exportcert ^
    -alias server ^
    -keystore server-keystore.jks ^
    -storepass "%PASSWORD%" ^
    -file server-cert.cer

echo ✅ Server certificate exported: server-cert.cer

REM =============================================================================
REM 2. Generate Client Certificate (for mTLS)
REM =============================================================================
echo.
echo 📄 Generating client certificate...

"%KEYTOOL%" -genkeypair ^
    -alias client ^
    -keyalg RSA ^
    -keysize %KEY_SIZE% ^
    -validity %VALIDITY_DAYS% ^
    -keystore client-keystore.jks ^
    -storepass "%PASSWORD%" ^
    -keypass "%PASSWORD%" ^
    -dname "CN=client, OU=Actors, O=Test, L=City, ST=State, C=US"

echo ✅ Client keystore created: client-keystore.jks

"%KEYTOOL%" -exportcert ^
    -alias client ^
    -keystore client-keystore.jks ^
    -storepass "%PASSWORD%" ^
    -file client-cert.cer

echo ✅ Client certificate exported: client-cert.cer

REM =============================================================================
REM 3. Create Truststore
REM =============================================================================
echo.
echo 📄 Creating truststore...

"%KEYTOOL%" -importcert ^
    -noprompt ^
    -alias server ^
    -file server-cert.cer ^
    -keystore truststore.jks ^
    -storepass "%PASSWORD%"

echo ✅ Server certificate imported to truststore

"%KEYTOOL%" -importcert ^
    -noprompt ^
    -alias client ^
    -file client-cert.cer ^
    -keystore truststore.jks ^
    -storepass "%PASSWORD%"

echo ✅ Client certificate imported to truststore

REM =============================================================================
REM 4. Verify Certificates
REM =============================================================================
echo.
echo 🔍 Verifying certificates...

echo.
echo Server keystore contents:
"%KEYTOOL%" -list -keystore server-keystore.jks -storepass "%PASSWORD%" | findstr "Alias"

echo.
echo Client keystore contents:
"%KEYTOOL%" -list -keystore client-keystore.jks -storepass "%PASSWORD%" | findstr "Alias"

echo.
echo Truststore contents:
"%KEYTOOL%" -list -keystore truststore.jks -storepass "%PASSWORD%" | findstr "Alias"

REM =============================================================================
REM 5. Generate Configuration
REM =============================================================================
echo.
echo 📝 Generating configuration...

set CURRENT_DIR=%CD%
set CURRENT_DIR=%CURRENT_DIR:\=/%

(
echo # TLS Configuration for Actor Remote Communication
echo # Generated on %date% %time%
echo.
echo # Server configuration
echo actor.remote.rsocket.tls.enabled=true
echo actor.remote.rsocket.tls.keyStore=%CURRENT_DIR%/server-keystore.jks
echo actor.remote.rsocket.tls.keyStorePassword=%PASSWORD%
echo actor.remote.rsocket.tls.trustStore=%CURRENT_DIR%/truststore.jks
echo actor.remote.rsocket.tls.trustStorePassword=%PASSWORD%
echo.
echo # Client configuration
echo actor.remote.client.tls.enabled=true
echo actor.remote.client.tls.keyStore=%CURRENT_DIR%/client-keystore.jks
echo actor.remote.client.tls.keyStorePassword=%PASSWORD%
echo actor.remote.client.tls.trustStore=%CURRENT_DIR%/truststore.jks
echo actor.remote.client.tls.trustStorePassword=%PASSWORD%
echo.
echo # Protocols (only modern, secure protocols^)
echo actor.remote.tls.protocols=TLSv1.3,TLSv1.2
) > tls-config.properties

echo ✅ Configuration generated: tls-config.properties

REM =============================================================================
REM 6. Copy to Test Resources (for classpath loading in tests)
REM =============================================================================
echo.
echo 📋 Copying certificates to test resources...

REM Go back to scripts directory
cd ..

set TEST_RESOURCES_DIR=..\src\test\resources\test-certs

if not exist "%TEST_RESOURCES_DIR%" mkdir "%TEST_RESOURCES_DIR%"

copy /Y certs\server-keystore.jks "%TEST_RESOURCES_DIR%\server-keystore.jks" >nul
copy /Y certs\client-keystore.jks "%TEST_RESOURCES_DIR%\client-keystore.jks" >nul
copy /Y certs\truststore.jks "%TEST_RESOURCES_DIR%\truststore.jks" >nul

if %ERRORLEVEL% EQU 0 (
    echo ✅ Certificates copied to test resources
    echo    Location: src\test\resources\test-certs\
    echo    Tests can now load certs from classpath: /test-certs/server-keystore.jks
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
echo   📄 server-keystore.jks    - Server private key and certificate
echo   📄 client-keystore.jks    - Client private key and certificate (mTLS^)
echo   📄 truststore.jks         - Trusted CA certificates
echo   📄 server-cert.cer        - Server certificate (for inspection^)
echo   📄 client-cert.cer        - Client certificate (for inspection^)
echo   📄 tls-config.properties  - Configuration file
echo.
echo Also copied to src/test/resources/test-certs/ for test classpath loading
echo.
echo ⚠️  WARNING: These are self-signed certificates for TESTING ONLY!
echo     Do NOT use in production. Generate proper CA-signed certificates.
echo.
echo Password for all keystores: %PASSWORD%
echo.
echo To use in your application:
echo   1. For tests: Certs are already in classpath - use '/test-certs/keystore.jks'
echo   2. For production: Generate proper certificates and configure paths
echo   3. Enable TLS: actor.remote.rsocket.tls.enabled = true
echo.

pause
endlocal
