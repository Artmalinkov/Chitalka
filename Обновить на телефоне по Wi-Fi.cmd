@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\Update-Android.ps1" -Build
if errorlevel 1 pause
