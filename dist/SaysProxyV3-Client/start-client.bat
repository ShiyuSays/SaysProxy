@echo off
chcp 65001 >nul
cd /d "%~dp0"
where javaw >nul 2>nul
if errorlevel 1 (
  echo [ERROR] javaw.exe not found. Install Java 8+ and add Java to PATH.
  pause
  exit /b 1
)
start "" javaw -Dfile.encoding=UTF-8 -jar "SaysProxyV3-Client.jar"
