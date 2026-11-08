@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-phone.ps1"
set "installResult=%errorlevel%"
pause
exit /b %installResult%
