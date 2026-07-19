#!/bin/bash
set -e
echo "[喵后台] 启动中..."

echo "[1/3] 检查 Docker..."
docker ps > /dev/null 2>&1 || { echo "[错误] Docker 未运行"; exit 1; }

echo "[2/3] 启动 PostgreSQL + Redis..."
docker compose -p miao up -d
sleep 3

echo "[3/3] 运行数据库迁移..."
node src/db/migrate.js

echo ""
echo "[喵后台] 启动服务器..."
echo "[喵后台] 访问地址: http://localhost:3000"
node src/index.js
