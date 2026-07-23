@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem Build the native camera engine shared library through the Android Gradle/NDK pipeline.
rem Usage:
rem   build-ndk.bat [Debug|Release] [abi]
rem Examples:
rem   build-ndk.bat
rem   build-ndk.bat Debug arm64-v8a
rem   build-ndk.bat Release x86_64

set "SCRIPT_DIR=%~dp0"
set "PROJECT_ROOT=%SCRIPT_DIR%..\..\..\..\"
pushd "%PROJECT_ROOT%" >nul
if errorlevel 1 (
    echo [ERROR] Failed to enter project root: %PROJECT_ROOT%
    exit /b 1
)

set "BUILD_TYPE=%~1"
if "%BUILD_TYPE%"=="" set "BUILD_TYPE=Debug"
if /I "%BUILD_TYPE%"=="debug" set "BUILD_TYPE=Debug"
if /I "%BUILD_TYPE%"=="release" set "BUILD_TYPE=Release"

if /I not "%BUILD_TYPE%"=="Debug" if /I not "%BUILD_TYPE%"=="Release" (
    echo [ERROR] Invalid build type: %BUILD_TYPE%
    echo Usage: build-ndk.bat [Debug^|Release] [abi]
    popd >nul
    exit /b 1
)

set "TARGET_ABI=%~2"

rem Prefer an existing JAVA_HOME, otherwise use common local JDK locations.
if not defined JAVA_HOME (
    if exist "C:\Program Files\Java\jdk-21\bin\java.exe" set "JAVA_HOME=C:\Program Files\Java\jdk-21"
)
if not defined JAVA_HOME (
    if exist "C:\Program Files\Java\jdk-23\bin\java.exe" set "JAVA_HOME=C:\Program Files\Java\jdk-23"
)
if not defined JAVA_HOME (
    if exist "C:\Program Files\Java\jdk-11.0.0.2\bin\java.exe" set "JAVA_HOME=C:\Program Files\Java\jdk-11.0.0.2"
)

if not defined JAVA_HOME (
    echo [ERROR] JAVA_HOME is not set and no supported JDK was found under C:\Program Files\Java.
    popd >nul
    exit /b 1
)

set "PATH=%JAVA_HOME%\bin;%PATH%"
set "GRADLEW=%PROJECT_ROOT%gradlew.bat"
if not exist "%GRADLEW%" (
    echo [ERROR] Gradle wrapper not found: %GRADLEW%
    popd >nul
    exit /b 1
)

set "TASK=:Application:externalNativeBuild%BUILD_TYPE%"

echo [INFO] Project root: %PROJECT_ROOT%
echo [INFO] JAVA_HOME: %JAVA_HOME%
echo [INFO] Build type: %BUILD_TYPE%
if not "%TARGET_ABI%"=="" echo [INFO] Target ABI: %TARGET_ABI%

if "%TARGET_ABI%"=="" (
    call "%GRADLEW%" %TASK%
) else (
    call "%GRADLEW%" %TASK% -PabiFilters=%TARGET_ABI%
)

if errorlevel 1 (
    echo [ERROR] Native build failed.
    popd >nul
    exit /b 1
)

echo.
echo [INFO] Native build completed successfully.
echo [INFO] Shared libraries are usually generated under:
echo        Application\.cxx\%BUILD_TYPE%\^<hash^>\^<abi^>\
echo        Application\build\intermediates\cxx\%BUILD_TYPE%\^<hash^>\obj\^<abi^>\

popd >nul
endlocal
