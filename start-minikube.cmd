@echo off
rem Double-click launcher for start-minikube.ps1 (bypasses the default script execution policy for this run only).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-minikube.ps1"
pause
