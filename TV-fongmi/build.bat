@echo off
REM ============================================================
REM  TV-fongmi 一键构建脚本（标准项目收尾）
REM  用法:  build.bat              -> 构建全部 release APK
REM         build.bat leanback     -> 只构建 leanback release
REM         build.bat mobile       -> 只构建 mobile release
REM  前置:  JDK 17 + Android SDK + Python 3.10（以下路径按需修改）
REM ============================================================
setlocal

REM ---- 环境（按需修改为你的本机路径）----
set JAVA_HOME=D:\AAA_soft\1_environmental\jdk-17.0.12
set ANDROID_SDK=D:\AAA_soft\1_environmental\SDK
set PYTHON310=D:\AAA_soft\1_environmental\WPy64-310100b1\python-3.10.10.amd64

REM ---- 签名配置（自动写入 local.properties，相对路径无中文依赖）----
> local.properties (
  echo sdk.dir=%ANDROID_SDK:\=\\%
  echo storeFile=../TV-release.jks
  echo keyAlias=fongmi
  echo storePassword=fongmi123456
)

REM ---- Python：确保 3.10 在 PATH 最前，供 Chaquopy 使用 ----
set PATH=%PYTHON310%;%JAVA_HOME%\bin;%PATH%
python.exe --version 2>nul
if errorlevel 1 (
  echo [X] 未找到 Python，请检查 PYTHON310 路径
  exit /b 1
)

REM ---- 清理 Chaquopy 旧 venv 缓存（防迁移后指向失效路径）----
if exist chaquo\build\python rmdir /s /q chaquo\build\python

REM ---- 选择变体 ----
set TASKS=:app:assembleLeanbackRelease :app:assembleMobileRelease
if "%1"=="leanback" set TASKS=:app:assembleLeanbackRelease
if "%1"=="mobile"   set TASKS=:app:assembleMobileRelease

echo.
echo ==== 开始构建: %TASKS% ====
call gradlew.bat --no-daemon %TASKS% --console=plain
set RC=%ERRORLEVEL%
if %RC%==0 (
  echo.
  echo ==== 构建成功！APK 位于: app\build\outputs\apk\ ====
) else (
  echo.
  echo [X] 构建失败，请查看上方日志
)
exit /b %RC%