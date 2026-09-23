@echo off
setlocal
cd /d "%~dp0"
chcp 65001 >nul

echo [UDP Chat] Khoi dong UDP Client...

if not defined JAVA_HOME (
    if exist "C:\Program Files\Java\jdk-21" (
        set "JAVA_HOME=C:\Program Files\Java\jdk-21"
    ) else if exist "C:\Program Files\Java\latest" (
        set "JAVA_HOME=C:\Program Files\Java\latest"
    )
)

call .\mvnw.cmd javafx:run -Pclient

if %ERRORLEVEL% neq 0 (
    echo [LOI] Khong the khoi dong Client. Vui long kiem tra lai Java 21 va Maven.
    pause
)
