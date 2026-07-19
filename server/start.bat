@echo off
echo [喵后台] 启动中...
cd /d %~dp0

echo [1/3] 检查 Docker...
docker ps >nul 2>&1
if errorlevel 1 (
    echo [错误] Docker Desktop 未运行，请先启动 Docker Desktop
    pause
    exit /b 1
)

echo [2/3] 启动 PostgreSQL + Redis...
docker compose -p miao up -d
timeout /t 3 /nobreak >nul

echo [3/3] 运行数据库迁移...
node src/db/migrate.js

echo.
echo [喵后台] 启动服务器...
echo [喵后台] 访问地址: http://localhost:3000
echo [喵后台] 健康检查: http://localhost:3000/health
echo [喵后台] 按 Ctrl+C 停止服务器
echo.
node src/index.js
