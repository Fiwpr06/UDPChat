@echo off
setlocal
cd /d "%~dp0"

echo ========================================================
echo   UDP CHAT SYSTEM - CHAY DEMO (1 SERVER + 2 CLIENTS)
echo ========================================================

echo 1. Dang khoi dong Server tren cua so moi...
start "UDP Server (Port 8888)" cmd /c "%~dp0run-server.bat"

echo Cho Server khoi dong trong 3 giay...
timeout /t 3 /nobreak >nul

echo 2. Dang khoi dong Client 1 (Alice)...
start "UDP Client 1" cmd /c "%~dp0run-client.bat"

timeout /t 2 /nobreak >nul

echo 3. Dang khoi dong Client 2 (Bob)...
start "UDP Client 2" cmd /c "%~dp0run-client.bat"

echo ========================================================
echo   Da khoi dong xong 1 Server va 2 Clients!
echo ========================================================
