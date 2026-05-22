@echo off
title Relab Backend (Daphne)
echo Starting Relab Backend...

:: Переходим в директорию скрипта
cd /d "%~dp0"

:: Проверка наличия виртуального окружения
if not exist ".venv\Scripts\activate" (
    echo [ERROR] .venv not found! Please create it first.
    pause
    exit /b
)

echo [1/2] Activating virtual environment...
call .venv\Scripts\activate

echo [2/2] Starting Daphne ASGI server on 0.0.0.0:8000...
echo Server will be accessible from Android app via your local IP.

daphne -b 0.0.0.0 -p 8000 backend_relab_app.asgi:application

pause
