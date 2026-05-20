@echo off
setlocal EnableDelayedExpansion

set "MAVEN_PROJECTBASEDIR=%~dp0"
set "WRAPPER_PROPERTIES=%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.properties"
set "MAVEN_HOME=%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-dist"

rem Find Java
if defined JAVA_HOME (
    set "JAVACMD=%JAVA_HOME%\bin\java.exe"
) else (
    for %%i in (java.exe) do set "JAVACMD=%%~$PATH:i"
)
if not exist "%JAVACMD%" (
    for /d %%d in ("C:\Program Files\Eclipse Adoptium\jdk-*") do (
        if exist "%%d\bin\java.exe" set "JAVACMD=%%d\bin\java.exe" & set "JAVA_HOME=%%d"
    )
)
if not exist "%JAVACMD%" (
    echo Error: Java not found. Set JAVA_HOME or add java to PATH. >&2
    exit /b 1
)

rem Download Maven if not present
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
    echo Downloading Maven...
    mkdir "%MAVEN_HOME%" 2>NUL

    rem Read distribution URL
    for /f "tokens=1,* delims==" %%a in ('findstr "distributionUrl" "%WRAPPER_PROPERTIES%"') do set "DIST_URL=%%b"
    if not defined DIST_URL (
        set "DIST_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip"
    )
    if "%DIST_URL%"=="" (
        set "DIST_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.6/apache-maven-3.9.6-bin.zip"
    )

    set "TMPFILE=%TEMP%\maven-dist.zip"
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri '!DIST_URL!' -OutFile '!TMPFILE!'" || (
        echo Error: Failed to download Maven >&2
        exit /b 1
    )

    set "TMPDIR=%TEMP%\maven-extract"
    mkdir "!TMPDIR!" 2>NUL
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '!TMPFILE!' -DestinationPath '!TMPDIR!' -Force"

    for /d %%d in ("!TMPDIR!\apache-maven-*") do (
        xcopy /s /e /y "%%d\*" "%MAVEN_HOME%\" >NUL
    )
    rmdir /s /q "!TMPDIR!" 2>NUL
    del "!TMPFILE!" 2>NUL
    echo Maven downloaded to %MAVEN_HOME%
)

"%MAVEN_HOME%\bin\mvn.cmd" %*
