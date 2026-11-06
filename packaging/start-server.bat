@echo off
chcp 65001 >nul
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] java.exe not found. Install Java 8+ and add it to PATH.
  pause
  exit /b 1
)
java -Dfile.encoding=UTF-8 -jar "SaysProxyV3-Server.jar"
echo.
echo Server process exited.
pause
