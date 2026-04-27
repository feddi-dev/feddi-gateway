@rem
@rem  feddi Gateway launcher script for Windows
@rem
@rem  Finds Java, validates version, and starts the feddi Gateway with tuned defaults.
@rem
@rem  Environment variables:
@rem    FEDDI_GATEWAY_JAVA_HOME - Java home for the feddi Gateway (takes priority over JAVA_HOME)
@rem    JAVA_HOME               - Standard Java home
@rem    JAVA_OPTS               - Additional JVM options (appended after defaults)
@rem

@if "%DEBUG%"=="" @echo off
@rem Set local scope for the variables
setlocal

set APP_HOME=%~dp0..

@rem --------------------------------------------------------------------------
@rem Find Java
@rem --------------------------------------------------------------------------
set JAVA_EXE=

if defined FEDDI_GATEWAY_JAVA_HOME (
    set "JAVA_EXE=%FEDDI_GATEWAY_JAVA_HOME%\bin\java.exe"
    if exist "%JAVA_EXE%" goto foundJava
)

if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
    if exist "%JAVA_EXE%" goto foundJava
)

@rem Try java on PATH
where java >nul 2>nul
if %ERRORLEVEL% equ 0 (
    set "JAVA_EXE=java"
    goto foundJava
)

echo.
echo ERROR: Java not found.
echo.
echo   Set JAVA_HOME or FEDDI_GATEWAY_JAVA_HOME, or add java to your PATH.
echo   feddi Gateway requires Java 25 or later.
echo.
exit /b 1

:foundJava

@rem --------------------------------------------------------------------------
@rem Validate Java version (require 25+)
@rem --------------------------------------------------------------------------
for /f "tokens=3" %%g in ('"%JAVA_EXE%" -version 2^>^&1 ^| findstr /i "version"') do (
    set JAVA_VERSION_STRING=%%g
)
@rem Remove surrounding quotes
set JAVA_VERSION_STRING=%JAVA_VERSION_STRING:"=%

@rem Extract major version (before the first dot)
for /f "delims=." %%a in ("%JAVA_VERSION_STRING%") do set JAVA_MAJOR=%%a

if %JAVA_MAJOR% LSS 25 (
    echo.
    echo ERROR: feddi Gateway requires Java 25 or later.
    echo.
    echo   Found: Java %JAVA_VERSION_STRING%
    echo   Java:  %JAVA_EXE%
    echo.
    echo   Set FEDDI_GATEWAY_JAVA_HOME or JAVA_HOME to a Java 25+ installation.
    echo.
    exit /b 1
)

@rem --------------------------------------------------------------------------
@rem Verify app.jar exists
@rem --------------------------------------------------------------------------
if not exist "%APP_HOME%\app.jar" (
    echo.
    echo ERROR: app.jar not found in %APP_HOME%
    echo.
    exit /b 1
)

@rem --------------------------------------------------------------------------
@rem Default JVM options
@rem --------------------------------------------------------------------------
set DEFAULT_JVM_OPTS=-XX:+UseZGC -Xms256m -Xmx1g -XX:SoftMaxHeapSize=768m -XX:+UseStringDeduplication -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:-OmitStackTraceInFastThrow

@rem --------------------------------------------------------------------------
@rem Launch the feddi Gateway
@rem --------------------------------------------------------------------------
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% -Dloader.path="%APP_HOME%\libs" -jar "%APP_HOME%\app.jar" %*

exit /b %ERRORLEVEL%
