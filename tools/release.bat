@echo off
cd /d "%~dp0.."
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0lib\release.ps1"
set "releaseResult=%errorlevel%"
pause
exit /b %releaseResult%
