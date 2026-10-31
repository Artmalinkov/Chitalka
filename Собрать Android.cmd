@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\Build-Android.ps1"
if errorlevel 1 pause
