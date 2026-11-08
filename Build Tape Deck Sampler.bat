@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1"
set "buildResult=%errorlevel%"
pause
exit /b %buildResult%
