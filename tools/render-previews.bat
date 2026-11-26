@echo off
cd /d "%~dp0.."
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0lib\render-previews.ps1"
set "renderResult=%errorlevel%"
pause
exit /b %renderResult%
