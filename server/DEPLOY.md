# 喵后台 · 部署文档

完整的服务器地址、默认凭据说明和内网映射端口表请见 [`../docs/操作手册.md`](../docs/操作手册.md)。

> 日期：2026-07-12

---

## 一、本地开发启动

### 前置要求
- Node.js 20+
- Docker Desktop（已运行）

### 步骤

```bash
cd server/

# 1. 安装依赖
npm install

# 2. 填写 .env 中的 AI API Key（至少填一个）
# 编辑 server/.env，填写 ANTHROPIC_API_KEY 或 OPENAI_API_KEY 等

# 3. 一键启动（Windows）
start.bat

# 一键启动（Mac/Linux）
chmod +x start.sh && ./start.sh
```

启动后访问 `http://localhost:3000/health` 验证服务正常。

### Android 模拟器连接本地服务器

Android 模拟器访问宿主机 IP 是 `10.0.2.2`，已在 `NetworkModule.kt` 中配置：

```kotlin
private const val BASE_URL = "http://10.0.2.2:3000/api/v1/"
```

**真机调试**：需要把 `10.0.2.2` 改为电脑的局域网 IP（如 `192.168.1.100`），并确保手机和电脑在同一 WiFi。

---

## 二、配置 AI API Key

编辑 `server/.env`：

```env
# 至少填一个，不填的模型在 App 中会显示不可用
ANTHROPIC_API_KEY=sk-ant-...     # Claude
OPENAI_API_KEY=sk-...            # GPT-4o
DEEPSEEK_API_KEY=sk-...          # DeepSeek
QWEN_API_KEY=sk-...              # 通义千问（阿里云 DashScope）
ZHIPU_API_KEY=...                # 智谱 GLM
```

---

## 三、生产服务器部署

### 服务器要求
- Ubuntu 22.04 LTS / Debian 12
- 2 核 2GB RAM 起步（推荐 4 核 4GB）
- 开放端口：80、443（对外），3000（内网）

### 部署步骤

```bash
# 1. 安装 Node.js 20
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs

# 2. 安装 Docker
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER

# 3. 克隆/上传代码
git clone <你的仓库> /opt/miao
cd /opt/miao/server
npm install --production

# 4. 配置环境变量
cp .env .env.prod
nano .env.prod   # 填入真实 API Key 和强密钥

# 5. 启动数据库
docker compose -p miao up -d
sleep 5
node src/db/migrate.js

# 6. 用 PM2 守护进程启动
npm install -g pm2
pm2 start src/index.js --name miao-server
pm2 save
pm2 startup   # 设置开机自启
```

### Nginx 反向代理（可选，用于 HTTPS）

```nginx
server {
    listen 80;
    server_name api.你的域名.com;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection 'upgrade';
        proxy_set_header Host $host;
        proxy_cache_bypass $http_upgrade;
        # 重要：SSE 需要关闭缓冲
        proxy_buffering off;
        proxy_read_timeout 300s;
    }
}
```

申请 SSL：
```bash
sudo apt install certbot python3-certbot-nginx
sudo certbot --nginx -d api.你的域名.com
```

### 更新 Android App 指向生产服务器

将 `NetworkModule.kt` 中的 BASE_URL 改为生产地址：
```kotlin
private const val BASE_URL = "https://api.你的域名.com/api/v1/"
```

---

## 四、API 接口概览

| 路径 | 方法 | 描述 |
|---|---|---|
| `/health` | GET | 健康检查（无需认证） |
| `/api/v1/auth/register` | POST | 注册 |
| `/api/v1/auth/login` | POST | 登录 |
| `/api/v1/auth/refresh` | POST | 刷新 token |
| `/api/v1/conversations` | GET/POST | 会话列表/创建 |
| `/api/v1/conversations/:id/chat` | POST | 发送消息（SSE 流式） |
| `/api/v1/memories` | GET/POST/PATCH/DELETE | 记忆 CRUD |
| `/api/v1/emojis/sync` | GET | 表情包增量同步 |
| `/api/v1/user/profile` | GET/PATCH | 个人信息 |
| `/api/v1/admin/*` | * | 后台管理（需 admin role） |

---

## 五、创建管理员账号

```bash
# 在服务器上执行（将示例地址改为实际管理员邮箱）
docker exec -it miao-postgres-1 psql -U miao -d miao -c \
  "UPDATE users SET role='admin' WHERE email='admin@example.com';"
```

---

## 六、数据库备份

```bash
# 备份
docker exec miao-postgres-1 pg_dump -U miao miao > backup_$(date +%Y%m%d).sql

# 恢复
docker exec -i miao-postgres-1 psql -U miao -d miao < backup_20260712.sql
```
