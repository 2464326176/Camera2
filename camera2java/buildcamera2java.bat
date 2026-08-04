@echo off
setlocal

REM Build script for Camera2All.
REM AGP 7.4.2 + Gradle 7.5.1 require JDK 11-17. This machine's default Gradle JVM is Java 8 and
REM the Android Studio JBR is Java 25 (both incompatible), so we point at the extracted Temurin JDK 17.
REM (gradle.properties also sets org.gradle.java.home to the same path as a fallback.)
set "JAVA_HOME=E:\dev\githubDesktop\Camera2\jdk17\jdk-17.0.20+8"

if exist "%JAVA_HOME%\bin\java.exe" (
    echo [INFO] Using JDK 17: %JAVA_HOME%
) else (
    echo [WARN] JDK 17 not found at %JAVA_HOME%; relying on org.gradle.java.home in gradle.properties
)

if "%~1"=="" (
    call "%~dp0gradlew.bat" assembleDebug
) else (
    call "%~dp0gradlew.bat" %*
)

endlocal
