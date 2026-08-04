@echo off
chcp 65001 >nul
setlocal EnableDelayedExpansion

REM ==========================================================================
REM  Camera2 APK 一键编译脚本 (Windows)
REM  双击运行：检测环境 -> 清理 -> 编译 -> 打包 -> 输出 APK 路径
REM ==========================================================================

set "PROJECT_DIR=%~dp0"
set "APK_PATH="

echo ==========================================================================
echo  Camera2 APK 一键编译脚本
echo  项目目录: %PROJECT_DIR%
echo ==========================================================================
echo.

REM --------------------------------------------------------------------------
REM 1. 检测 JDK 环境
REM --------------------------------------------------------------------------
echo [1/5] 正在检测编译环境...
echo.

set "JAVA_OK=0"
set "JAVA_BIN="

REM 优先使用项目 local.properties 中指定的 JDK
if exist "%PROJECT_DIR%local.properties" (
    for /f "usebackq tokens=1,* delims==" %%A in ("%PROJECT_DIR%local.properties") do (
        if "%%A"=="org.gradle.java.home" (
            set "PROP_JAVA=%%B"
        )
    )
)

REM 规整路径中的转义反斜杠 (local.properties 中可能是 D\:\\xxx 形式)
if defined PROP_JAVA (
    set "PROP_JAVA=!PROP_JAVA:\:=:!"
    set "PROP_JAVA=!PROP_JAVA:\\=\!"
)

REM 依次尝试：local.properties -> JAVA_HOME -> PATH
if defined PROP_JAVA (
    if exist "!PROP_JAVA!\bin\java.exe" (
        set "JAVA_BIN=!PROP_JAVA!\bin\java.exe"
        set "JAVA_OK=1"
    )
)
if !JAVA_OK!==0 (
    if defined JAVA_HOME (
        set "JH=!JAVA_HOME:"=!"
        if exist "!JH!\bin\java.exe" (
            set "JAVA_BIN=!JH!\bin\java.exe"
            set "JAVA_OK=1"
        )
    )
)
if !JAVA_OK!==0 (
    where java.exe >nul 2>&1
    if not errorlevel 1 (
        set "JAVA_BIN=java.exe"
        set "JAVA_OK=1"
    )
)

if !JAVA_OK!==0 (
    echo [错误] 未检测到 JDK，即 Java Development Kit。
    echo.
    echo 请按以下任一方式配置 JDK：
    echo   方式一：编辑项目下的 local.properties，添加一行：
    echo            org.gradle.java.home=你的JDK路径
    echo          例如：org.gradle.java.home=D:\\lyh\\java\\.jdks\\ms-11.0.32
    echo   方式二：设置系统环境变量 JAVA_HOME 指向 JDK 安装目录。
    echo   方式三：将 JDK 的 bin 目录加入系统 PATH。
    echo.
    echo 注意：本工程使用 Android Gradle Plugin 7.4.2，需要 JDK 11 或更高版本。
    echo.
    pause
    exit /b 1
)
echo        JDK 已就绪: !JAVA_BIN!
"!JAVA_BIN!" -version 2>&1 | findstr /i "version"
echo.

REM 让 Gradle 使用检测到的 JDK 启动 (避免 JAVA_HOME 指向旧版 Java 导致 AGP 不兼容)
if defined PROP_JAVA (
    set "JAVA_HOME=!PROP_JAVA!"
) else if defined JAVA_HOME (
    set "JAVA_HOME=!JAVA_HOME:"=!"
)

REM --------------------------------------------------------------------------
REM 2. 检测 Android SDK 环境
REM --------------------------------------------------------------------------
set "SDK_OK=0"
set "SDK_DIR="

if exist "%PROJECT_DIR%local.properties" (
    for /f "usebackq tokens=1,* delims==" %%A in ("%PROJECT_DIR%local.properties") do (
        if "%%A"=="sdk.dir" (
            set "PROP_SDK=%%B"
        )
    )
)
if defined PROP_SDK (
    set "PROP_SDK=!PROP_SDK:\:=:!"
    set "PROP_SDK=!PROP_SDK:\\=\!"
)

if defined PROP_SDK (
    if exist "!PROP_SDK!" (
        set "SDK_DIR=!PROP_SDK!"
        set "SDK_OK=1"
    )
)
if !SDK_OK!==0 (
    if defined ANDROID_HOME (
        if exist "!ANDROID_HOME!" (
            set "SDK_DIR=!ANDROID_HOME!"
            set "SDK_OK=1"
        )
    )
)
if !SDK_OK!==0 (
    if defined ANDROID_SDK_ROOT (
        if exist "!ANDROID_SDK_ROOT!" (
            set "SDK_DIR=!ANDROID_SDK_ROOT!"
            set "SDK_OK=1"
        )
    )
)

if !SDK_OK!==0 (
    echo [错误] 未检测到 Android SDK。
    echo.
    echo 请按以下任一方式配置 Android SDK：
    echo   方式一：编辑项目下的 local.properties，添加一行：
    echo            sdk.dir=你的SDK路径
    echo          例如：sdk.dir=D:\\lyh\\Android\\android-sdk
    echo   方式二：设置系统环境变量 ANDROID_HOME 指向 SDK 安装目录。
    echo.
    pause
    exit /b 1
)
echo        Android SDK 已就绪: !SDK_DIR!
echo.

REM --------------------------------------------------------------------------
REM 3. 清理旧的构建产物
REM --------------------------------------------------------------------------
echo [2/5] 正在清理旧的构建产物，执行 gradlew clean...
echo.
call "%PROJECT_DIR%gradlew.bat" clean
if errorlevel 1 (
    echo.
    echo [错误] 清理构建产物失败。请查看上方日志。
    echo.
    pause
    exit /b 1
)
echo       清理完成。
echo.

REM --------------------------------------------------------------------------
REM 4. 编译并打包生成 APK
REM --------------------------------------------------------------------------
echo [3/5] 正在编译并打包生成 APK，执行 assembleDebug...
echo.
call "%PROJECT_DIR%gradlew.bat" assembleDebug --stacktrace
if errorlevel 1 (
    echo.
    echo [错误] 编译失败！请查看上方 Gradle 错误日志定位问题。
    echo.
    pause
    exit /b 1
)
echo.
echo       编译打包完成。
echo.

REM --------------------------------------------------------------------------
REM 5. 查找并输出生成的 APK 文件路径
REM --------------------------------------------------------------------------
echo [4/5] 正在定位生成的 APK 文件...
echo.

set "APK_FOUND=0"
for /r "%PROJECT_DIR%app\build\outputs\apk" %%F in (*.apk) do (
    if exist "%%F" (
        set "APK_PATH=%%F"
        set "APK_FOUND=1"
    )
)

if !APK_FOUND!==0 (
    echo [警告] 未在预期目录中找到 APK 文件：
    echo          %PROJECT_DIR%app\build\outputs\apk
    echo.
    echo 编译虽已完成，但未能自动定位 APK，请手动检查 build 输出目录。
    echo.
    pause
    exit /b 0
)

echo ==========================================================================
echo  [5/5] 构建成功！
echo --------------------------------------------------------------------------
for %%I in ("!APK_PATH!") do (
    echo  APK 文件名 : %%~nxI
)
echo  APK 完整路径: !APK_PATH!
echo ==========================================================================
echo.

pause
endlocal
exit /b 0
