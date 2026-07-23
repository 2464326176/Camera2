@echo off
setlocal EnableExtensions EnableDelayedExpansion

rem Build the native camera engine shared library directly with Android NDK CMake.
rem The generated .so files are copied to Application\src\main\jniLibs\<abi>.
rem Usage:
rem   build-ndk.bat [Debug|Release] [abi|all]
rem Examples:
rem   build-ndk.bat
rem   build-ndk.bat Debug arm64-v8a
rem   build-ndk.bat Release all

set "SCRIPT_DIR=%~dp0"
set "PROJECT_ROOT=%SCRIPT_DIR%..\..\..\..\"
set "APP_DIR=%PROJECT_ROOT%Application"
set "LOCAL_PROPERTIES=%PROJECT_ROOT%local.properties"
set "CMAKE_LISTS=%APP_DIR%\CMakeLists.txt"
set "JNI_LIBS_DIR=%APP_DIR%\src\main\jniLibs"
set "BUILD_ROOT=%APP_DIR%\build\ndk-cmake"

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
    echo Usage: build-ndk.bat [Debug^|Release] [abi^|all]
    popd >nul
    exit /b 1
)

set "TARGET_ABI=%~2"
if "%TARGET_ABI%"=="" set "TARGET_ABI=arm64-v8a"
if /I "%TARGET_ABI%"=="all" (
    set "ABI_LIST=arm64-v8a armeabi-v7a x86 x86_64"
) else (
    set "ABI_LIST=%TARGET_ABI%"
)

call :read_local_property sdk.dir SDK_DIR
call :read_local_property ndk.dir NDK_DIR
call :read_local_property cmake.dir CMAKE_DIR
call :read_local_property opencv.dir OPENCV_SDK_PATH

if not defined SDK_DIR (
    if defined ANDROID_HOME set "SDK_DIR=%ANDROID_HOME%"
)
if not defined SDK_DIR (
    if defined ANDROID_SDK_ROOT set "SDK_DIR=%ANDROID_SDK_ROOT%"
)
if not defined SDK_DIR (
    echo [ERROR] sdk.dir was not found in local.properties and ANDROID_HOME/ANDROID_SDK_ROOT is not set.
    popd >nul
    exit /b 1
)

if not defined NDK_DIR call :find_latest_dir "%SDK_DIR%\ndk" NDK_DIR
if not defined NDK_DIR if exist "%SDK_DIR%\ndk-bundle\build\cmake\android.toolchain.cmake" set "NDK_DIR=%SDK_DIR%\ndk-bundle"
if not defined NDK_DIR (
    echo [ERROR] Android NDK was not found. Set ndk.dir in local.properties or install it under: %SDK_DIR%\ndk
    popd >nul
    exit /b 1
)

if not exist "%NDK_DIR%\build\cmake\android.toolchain.cmake" (
    echo [ERROR] Invalid NDK directory: %NDK_DIR%
    echo [ERROR] Missing: %NDK_DIR%\build\cmake\android.toolchain.cmake
    popd >nul
    exit /b 1
)

if not defined CMAKE_DIR call :find_latest_dir "%SDK_DIR%\cmake" CMAKE_DIR
if defined CMAKE_DIR set "CMAKE_EXE=%CMAKE_DIR%\bin\cmake.exe"
if not exist "%CMAKE_EXE%" set "CMAKE_EXE=%NDK_DIR%\prebuilt\windows-x86_64\bin\cmake.exe"
if not exist "%CMAKE_EXE%" set "CMAKE_EXE=cmake.exe"

if defined CMAKE_DIR set "NINJA_EXE=%CMAKE_DIR%\bin\ninja.exe"
if not exist "%NINJA_EXE%" set "NINJA_EXE=%NDK_DIR%\prebuilt\windows-x86_64\bin\ninja.exe"
if not exist "%NINJA_EXE%" set "NINJA_EXE=ninja.exe"

if not exist "%CMAKE_LISTS%" (
    echo [ERROR] CMakeLists.txt not found: %CMAKE_LISTS%
    popd >nul
    exit /b 1
)
if not defined OPENCV_SDK_PATH (
    echo [ERROR] opencv.dir was not found in local.properties.
    popd >nul
    exit /b 1
)
if not exist "%OPENCV_SDK_PATH%\sdk\native" (
    echo [ERROR] Invalid OpenCV SDK path: %OPENCV_SDK_PATH%
    echo [ERROR] Missing: %OPENCV_SDK_PATH%\sdk\native
    popd >nul
    exit /b 1
)

echo [INFO] Project root: %PROJECT_ROOT%
echo [INFO] SDK dir: %SDK_DIR%
echo [INFO] NDK dir: %NDK_DIR%
echo [INFO] CMake: %CMAKE_EXE%
echo [INFO] Ninja: %NINJA_EXE%
echo [INFO] OpenCV SDK: %OPENCV_SDK_PATH%
echo [INFO] Build type: %BUILD_TYPE%
echo [INFO] ABI list: %ABI_LIST%

for %%A in (%ABI_LIST%) do (
    call :build_one_abi "%%A"
    if errorlevel 1 (
        popd >nul
        exit /b 1
    )
)

echo.
echo [INFO] Native build completed successfully.
echo [INFO] Shared libraries copied to: %JNI_LIBS_DIR%

popd >nul
endlocal
exit /b 0

:build_one_abi
set "ABI=%~1"
set "ABI_BUILD_DIR=%BUILD_ROOT%\%BUILD_TYPE%\%ABI%"
set "ABI_OUTPUT_DIR=%JNI_LIBS_DIR%\%ABI%"
set "OPENCV_SO=%OPENCV_SDK_PATH%\sdk\native\libs\%ABI%\libopencv_java4.so"

if not exist "%OPENCV_SO%" (
    echo [ERROR] OpenCV shared library not found for ABI %ABI%: %OPENCV_SO%
    exit /b 1
)

echo.
echo [INFO] Configuring %ABI% %BUILD_TYPE%...
"%CMAKE_EXE%" ^
    -S "%APP_DIR%" ^
    -B "%ABI_BUILD_DIR%" ^
    -G Ninja ^
    -DANDROID_ABI=%ABI% ^
    -DANDROID_PLATFORM=android-24 ^
    -DANDROID_STL=c++_shared ^
    -DANDROID_TOOLCHAIN=clang ^
    -DCMAKE_BUILD_TYPE=%BUILD_TYPE% ^
    -DCMAKE_TOOLCHAIN_FILE="%NDK_DIR%\build\cmake\android.toolchain.cmake" ^
    -DCMAKE_MAKE_PROGRAM="%NINJA_EXE%" ^
    -DOPENCV_SDK_PATH="%OPENCV_SDK_PATH%"
if errorlevel 1 (
    echo [ERROR] CMake configure failed for ABI %ABI%.
    exit /b 1
)

echo [INFO] Building %ABI% %BUILD_TYPE%...
"%CMAKE_EXE%" --build "%ABI_BUILD_DIR%" --target camera_engine --config %BUILD_TYPE%
if errorlevel 1 (
    echo [ERROR] Native build failed for ABI %ABI%.
    exit /b 1
)

if not exist "%ABI_OUTPUT_DIR%" mkdir "%ABI_OUTPUT_DIR%"
copy /Y "%ABI_BUILD_DIR%\libcamera_engine.so" "%ABI_OUTPUT_DIR%\libcamera_engine.so" >nul
if errorlevel 1 (
    echo [ERROR] Failed to copy libcamera_engine.so for ABI %ABI%.
    exit /b 1
)
copy /Y "%OPENCV_SO%" "%ABI_OUTPUT_DIR%\libopencv_java4.so" >nul
if errorlevel 1 (
    echo [ERROR] Failed to copy libopencv_java4.so for ABI %ABI%.
    exit /b 1
)

echo [INFO] Copied outputs for %ABI% to %ABI_OUTPUT_DIR%
exit /b 0

:read_local_property
set "PROP_KEY=%~1"
set "PROP_VAR=%~2"
if not exist "%LOCAL_PROPERTIES%" exit /b 0
for /f "usebackq tokens=1,* delims==" %%K in (`findstr /b /c:"%PROP_KEY%=" "%LOCAL_PROPERTIES%"`) do (
    set "PROP_VALUE=%%L"
    set "PROP_VALUE=!PROP_VALUE:\:=:!"
    set "PROP_VALUE=!PROP_VALUE:\\=\!"
    set "%PROP_VAR%=!PROP_VALUE!"
)
exit /b 0

:find_latest_dir
set "SEARCH_DIR=%~1"
set "RESULT_VAR=%~2"
set "LATEST_DIR="
if exist "%SEARCH_DIR%" (
    for /f "delims=" %%D in ('dir /b /ad /o-n "%SEARCH_DIR%" 2^>nul') do (
        if not defined LATEST_DIR set "LATEST_DIR=%SEARCH_DIR%\%%D"
    )
)
if defined LATEST_DIR set "%RESULT_VAR%=%LATEST_DIR%"
exit /b 0
