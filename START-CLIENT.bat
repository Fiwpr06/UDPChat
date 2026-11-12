@echo off
setlocal
cd /d "%~dp0"
chcp 65001 >nul
title UDP Mail Client

echo ========================================================
echo   UDP Mail Client
echo ========================================================
echo.

call .\mvnw.cmd javafx:run -Pclient

if %ERRORLEVEL% neq 0 (
    echo [LOI] Khong the khoi dong UDP Mail Client. Vui long kiem tra JDK 21.
    pause
)
