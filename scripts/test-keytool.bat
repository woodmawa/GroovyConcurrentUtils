@echo off
REM Test if keytool is available

echo Testing for keytool...
keytool -help >nul 2>&1

if %ERRORLEVEL% EQU 0 (
    echo ✅ keytool is available
    keytool -version
) else (
    echo ❌ keytool NOT found in PATH
    echo.
    echo keytool is part of the JDK. Please ensure:
    echo 1. JDK is installed (not just JRE)
    echo 2. JAVA_HOME is set to JDK location
    echo 3. %%JAVA_HOME%%\bin is in your PATH
    echo.
    echo Example:
    echo   set JAVA_HOME=C:\Program Files\Java\jdk-17
    echo   set PATH=%%JAVA_HOME%%\bin;%%PATH%%
)

pause
