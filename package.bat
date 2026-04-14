@echo off
echo Создание установщика Shpak Launcher...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0package.ps1"
pause
