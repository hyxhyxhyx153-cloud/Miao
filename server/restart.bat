@echo off
echo [喵] 重启 API 服务器...

REM 只杀占用 3000 端口的进程
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":3000 "') do (
    taskkill /F /PID %%a >nul 2>&1
)

timeout /t 2 /nobreak >nul

cd /d %~dp0
start "Miao API Server" node src/index.js
echo [喵] API 服务器已重启 (port 3000)
