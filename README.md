<div align="center">
  <img src="./app/src/main/res/drawable/_1de21655_6f0c_4b06_9b54_3101c192145f.png" width="112" alt="喵 Miao 图标" />
  <h1>喵（Miao）</h1>
  <p>可自托管的猫娘主题 AI 陪伴聊天系统</p>
  <p>
    <img alt="Android API 24+" src="https://img.shields.io/badge/Android-API%2024%2B-3DDC84?logo=android&logoColor=white" />
    <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?logo=kotlin&logoColor=white" />
    <img alt="Node.js 20+" src="https://img.shields.io/badge/Node.js-20%2B-339933?logo=nodedotjs&logoColor=white" />
    <img alt="Vue 3" src="https://img.shields.io/badge/Vue-3-42B883?logo=vuedotjs&logoColor=white" />
    <a href="./LICENSE"><img alt="MIT License" src="https://img.shields.io/badge/License-MIT-blue.svg" /></a>
  </p>
</div>

喵（Miao）是一套完整的 AI 陪伴聊天应用，由 Android 客户端、Fastify API 服务和 Vue 管理后台组成。用户注册后即可聊天，无需在手机端填写模型 API Key；部署者在管理后台统一配置模型、配额、人格、表情包、公告与应用版本。

项目支持多会话云同步、SSE 流式回复、图片与语音输入、长期记忆、自定义人格、表情包、离线缓存，以及通过 ClawBot/iLink 通道进行微信绑定。它适合个人自托管、二次开发，也可以作为完整的 Android + Node.js + Vue 全栈项目参考。

> [!IMPORTANT]
> 本项目不提供任何默认管理员密码或模型 API Key。首次部署必须自行创建管理员，并配置至少一个可用的模型服务。生产环境还必须替换示例密钥、启用 HTTPS，并保护 PostgreSQL 与 Redis 不被公网直接访问。

## 目录

- [主要能力](#主要能力)
- [系统架构](#系统架构)
- [技术栈](#技术栈)
- [仓库结构](#仓库结构)
- [快速开始](#快速开始)
- [创建首个管理员](#创建首个管理员)
- [配置 Android API 地址](#配置-android-api-地址)
- [服务端配置](#服务端配置)
- [API 概览](#api-概览)
- [测试与质量检查](#测试与质量检查)
- [生产部署](#生产部署)
- [安全说明](#安全说明)
- [常见问题](#常见问题)
- [参与贡献](#参与贡献)
- [许可证](#许可证)

## 主要能力

### Android 客户端

- 账号注册、用户名或邮箱登录、Refresh Token 轮换、找回与重置密码。
- 多会话管理、搜索、置顶、未读状态、撤回、删除和云端同步。
- SSE 流式聊天、停止生成、重试、重新生成、Markdown 与代码块复制。
- 最多九张图片的多模态消息、系统语音转文字和全屏图片预览。
- 猫娘情绪、动作提示、自动或手动表情包，以及表情包本地增量缓存。
- 手动与自动长期记忆、语义检索及无向量服务时的词法回退。
- 动态模型、自定义人格、会话级参数与双方头像设置。
- Room 离线缓存、离线消息幂等同步、DataStore 偏好和加密 Token 存储。
- 深浅色、主题色、字号、触感、流式开关等外观与交互偏好。
- 公告、用户协议、隐私政策、版本检查、APK 更新与微信扫码绑定。

### API 服务

- Fastify ES Module API，统一承载鉴权、业务数据、模型代理和 SSE 流。
- 支持 Anthropic、OpenAI、DeepSeek、Qwen、智谱等模型服务及自定义 Base URL。
- 模型 API Key 与微信凭据使用 AES-256-GCM 加密后入库，客户端不会接触明文 Key。
- 用户配额、请求限流、模型启停、视觉能力校验和多 Key 故障切换。
- 会话、消息、人格、记忆、表情包、设置、公告和应用版本的云端存储。
- 图片直传阿里云 OSS；未配置 OSS 时自动使用本地 `server/uploads/`。
- SMTP 密码重置邮件、API Key 用量告警和开发环境重置令牌回退。
- 微信 Worker 长轮询、消息持久化、故障恢复、重启和解绑。
- 管理操作审计、请求日志、统计聚合、IP 白名单和优雅关闭。

### Web 管理后台

- 仪表盘：活跃用户、消息量、Token、错误率、趋势和模型分布。
- 用户管理：搜索、详情、封禁、启停和配额调整。
- API Key 与模型管理：加密保存、连通测试、模型发现、启停和视觉标记。
- 表情包运营：上传、批量导入、AI 识别、统计和任务历史。
- 人格管理、微信 Worker 监控、请求日志与系统运行监控。
- 应用发布、APK 上传、升级策略、公告、隐私政策和用户协议管理。
- 系统设置、管理员账号、IP 白名单与不可变更的审计记录。

## 系统架构

```mermaid
flowchart LR
    APP[Android App\nJetpack Compose] -->|HTTPS + JWT\nSSE / JSON| API[Fastify API\n/api/v1]
    ADMIN[Vue Admin\nVite + Element Plus] -->|HTTPS + Admin JWT| API
    API --> PG[(PostgreSQL 16)]
    API --> REDIS[(Redis 7)]
    API --> MODELS[AI Providers\nAnthropic / OpenAI / DeepSeek / Qwen / GLM]
    API --> STORAGE[OSS 或本地上传目录]
    API --> SMTP[SMTP 邮件服务]
    API --> WECHAT[ClawBot / iLink\n微信消息通道]
```

Android 端以服务端数据为主源，Room 负责离线缓存与待同步消息；管理后台在开发环境通过 Vite 代理访问 API，生产环境推荐与 API 部署在同一 HTTPS 域名下。PostgreSQL 保存业务数据；Redis 当前用于 API Key 告警去重，并在服务启动阶段作为必需依赖连接。

## 技术栈

| 模块 | 主要技术 |
|---|---|
| Android | Kotlin 2.2、Jetpack Compose、Material 3、Navigation Compose、Hilt、Room、Retrofit、OkHttp、DataStore、Coil、Markwon |
| API | Node.js 20+、Fastify 5、PostgreSQL 16、Redis 7、JWT、bcrypt、官方/兼容 AI SDK、SSE |
| Admin | Vue 3、Vite 6、Vue Router、Pinia、Element Plus、Axios、ECharts |
| 工程 | Gradle 9.4.1、Android Gradle Plugin 9.2.1、Docker Compose、`node:test` |

当前 Android 应用版本为 `1.0.0`（`versionCode 3`），最低支持 Android 7.0（API 24），`compileSdk` 为 37，`targetSdk` 为 36。

## 仓库结构

```text
Miao/
├── app/                         # Android 客户端
│   └── src/
│       ├── main/java/com/hyx/miao/
│       │   ├── data/            # Room、Retrofit、Repository、DTO
│       │   ├── di/              # Hilt 依赖注入
│       │   └── ui/              # Compose 页面、组件、主题与导航
│       ├── test/                # JVM 单元测试
│       └── androidTest/         # 设备与 Compose 测试
├── server/                      # Fastify API
│   ├── src/
│   │   ├── routes/              # 用户、聊天、管理等路由
│   │   ├── services/            # 模型、记忆、微信、媒体等服务
│   │   ├── db/                  # PostgreSQL/Redis 与迁移
│   │   └── tests/               # 单元与集成测试
│   ├── .env.example             # 环境变量模板
│   └── docker-compose.yml       # PostgreSQL + Redis 开发依赖
├── admin/                       # Vue 管理后台
│   └── src/
│       ├── views/               # 管理页面
│       ├── router/              # 前端路由
│       └── styles/              # 全局主题
├── docs/                        # 产品、架构、功能与操作文档
└── gradle/                      # Gradle Wrapper 与版本目录
```

更深入的说明见：

- [`docs/产品文档.md`](./docs/产品文档.md)：产品定位和版本方向。
- [`docs/功能文档.md`](./docs/功能文档.md)：客户端功能与交互细节。
- [`docs/系统架构文档.md`](./docs/系统架构文档.md)：服务、数据流和微信接入架构。
- [`docs/后台管理端文档.md`](./docs/后台管理端文档.md)：后台功能说明。
- [`docs/操作手册.md`](./docs/操作手册.md)：地址、端口、管理员与运维操作。
- [`server/DEPLOY.md`](./server/DEPLOY.md)：服务端部署参考。

## 快速开始

### 1. 环境要求

| 依赖 | 要求 |
|---|---|
| Git | 用于获取代码和管理变更 |
| Node.js | 20 或更高版本，附带 npm |
| Docker | Docker Desktop 或 Docker Engine，并支持 Compose |
| Android 工具链 | 支持当前 AGP 的 Android Studio、JDK/JBR 17+、Android SDK Platform 37 |
| Android 设备 | Android 7.0（API 24）或更高版本的真机/模拟器 |

Android 源码的 Java 兼容目标为 11，但运行当前 Gradle/AGP 构建工具建议使用 Android Studio 自带的 JBR 17 或更高版本。

### 2. 获取代码并安装依赖

```bash
git clone https://github.com/hyxhyxhyx153-cloud/Miao.git
cd Miao

npm --prefix server ci
npm --prefix admin ci
```

### 3. 创建服务端配置

PowerShell：

```powershell
Copy-Item server/.env.example server/.env
```

macOS / Linux：

```bash
cp server/.env.example server/.env
```

编辑 `server/.env`，至少确认数据库、Redis 和三个安全密钥。生产环境中的三个密钥必须分别使用不同的高强度随机值：

```env
DATABASE_URL=postgresql://miao:miao-local-only@localhost:5432/miao
REDIS_URL=redis://localhost:6379

JWT_SECRET=replace-with-a-long-random-secret
JWT_REFRESH_SECRET=replace-with-another-long-random-secret
DATA_ENCRYPTION_KEY=replace-with-a-stable-random-encryption-secret
```

可使用下面的命令生成一个 32 字节十六进制随机值；执行三次并分别填写：

```bash
node -e "console.log(require('node:crypto').randomBytes(32).toString('hex'))"
```

> [!CAUTION]
> `DATA_ENCRYPTION_KEY` 用于解密已经保存的模型 API Key 和微信凭据。部署产生数据后不要随意修改或丢失该值，否则现有密文将无法恢复。

### 4. 启动 PostgreSQL 和 Redis

```bash
docker compose -p miao -f server/docker-compose.yml up -d
docker compose -p miao -f server/docker-compose.yml ps
```

Compose 中的数据库账号仅用于本地开发。生产环境必须修改数据库密码，并同步修改 `DATABASE_URL`。

### 5. 初始化数据库并启动 API

```bash
npm --prefix server run db:migrate
npm --prefix server run dev
```

看到服务启动日志后检查：

```bash
curl http://localhost:3000/health
```

预期返回包含 `"status":"ok"` 的 JSON。

Windows 也可以在安装依赖并创建 `.env` 后运行 `server\start.bat`；macOS/Linux 可运行 `server/start.sh`。两个脚本都会启动 Docker 依赖、执行迁移并启动 API。

### 6. 启动管理后台

另开一个终端：

```bash
npm --prefix admin run dev
```

访问 <http://localhost:5173>。Vite 会把 `/api/v1` 和 `/health` 代理到 `http://localhost:3000`。

### 7. 构建 Android 客户端

先按下一节设置本地 API 地址，然后在 Windows 中执行：

```powershell
.\gradlew.bat assembleDebug
```

macOS / Linux：

```bash
./gradlew assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。也可以在 Android Studio 中打开仓库根目录，选择 `app` 配置后运行到模拟器或真机。

## 创建首个管理员

项目没有硬编码的默认管理员，也不能从密码哈希中读取明文密码。首次部署按以下步骤创建：

1. 在 Android 客户端注册一个普通账号；也可以直接调用注册接口：

   ```bash
   curl -X POST http://localhost:3000/api/v1/auth/register \
     -H "Content-Type: application/json" \
     -d '{"username":"admin","email":"admin@example.com","password":"ChangeMe-Now-123"}'
   ```

2. 将该账号提升为管理员：

   ```bash
   docker compose -p miao -f server/docker-compose.yml exec postgres \
     psql -U miao -d miao \
     -c "UPDATE users SET role='admin' WHERE email='admin@example.com';"
   ```

3. 使用刚才设置的邮箱或用户名和密码登录 <http://localhost:5173>。
4. 在“API Key”页面添加至少一个模型服务凭据并测试连通性，在“模型管理”中确认所需模型已经启用。

生产部署应立即更换示例密码，并至少保留一个可靠的管理员恢复方案。管理员 JWT 保存在浏览器 `sessionStorage` 中，关闭会话后需要重新登录。

## 配置 Android API 地址

Android API 地址由 Gradle 属性注入，优先级如下：

1. `MIAO_API_BASE_URL`：强制所有构建使用同一个地址。
2. 模拟器使用 `MIAO_EMULATOR_API_BASE_URL`。
3. 真机使用 `MIAO_LAN_API_BASE_URL`。

推荐将开发机专用配置写入用户级 Gradle 配置，而不是提交到仓库：

- Windows：`%USERPROFILE%\.gradle\gradle.properties`
- macOS / Linux：`~/.gradle/gradle.properties`

```properties
# Android Studio 模拟器访问宿主机
MIAO_EMULATOR_API_BASE_URL=http://10.0.2.2:3000/api/v1/

# 真机访问同一局域网中的开发电脑
MIAO_LAN_API_BASE_URL=http://192.168.1.100:3000/api/v1/

# 正式构建可使用这一项覆盖上面两项
# MIAO_API_BASE_URL=https://miao.example.com/api/v1/
```

注意：

- 地址必须以 `http://` 或 `https://` 开头，并包含 `/api/v1/` 路径。
- Android 模拟器中的 `localhost` 指向模拟器自身；访问开发电脑应使用 `10.0.2.2`。
- 真机需要与服务器网络互通，并允许防火墙访问 API 端口。
- 开发构建允许局域网 HTTP；正式环境应始终使用受信任证书的 HTTPS。
- 修改 Gradle 属性后需要重新构建并安装 App。

也可以仅对单次命令传入覆盖地址：

```powershell
.\gradlew.bat assembleDebug "-PMIAO_API_BASE_URL=http://10.0.2.2:3000/api/v1/"
```

## 服务端配置

完整模板位于 [`server/.env.example`](./server/.env.example)。常用配置如下：

| 变量 | 用途 | 是否必需 |
|---|---|---|
| `PORT` | Fastify 监听端口，默认 `3000` | 否 |
| `NODE_ENV` | `development` 或 `production` | 生产建议显式设置 |
| `LOCAL_BASE_URL` | 客户端能访问的服务端源地址，不含 `/api/v1` | 上传、生成图片或 APK 发布时需要正确配置 |
| `CORS_ORIGINS` | 允许的管理端源，多个值用逗号分隔 | 跨域部署时需要 |
| `TRUST_PROXY` | 是否信任反向代理的客户端 IP 头 | 仅在受控代理后设为 `true` |
| `DATABASE_URL` | PostgreSQL 连接字符串 | 是 |
| `REDIS_URL` | Redis 连接字符串 | 是 |
| `JWT_SECRET` | Access Token 签名密钥 | 生产必需 |
| `JWT_REFRESH_SECRET` | Refresh Token 签名密钥 | 生产必需 |
| `DATA_ENCRYPTION_KEY` | 模型 Key 与微信凭据的稳定加密根密钥 | 生产必需 |
| `ANTHROPIC_API_KEY` | Anthropic 模型凭据 | 按需，亦可在后台配置 |
| `OPENAI_API_KEY` | OpenAI 聊天与默认 Embedding 凭据 | 按需，亦可在后台配置 |
| `DEEPSEEK_API_KEY` | DeepSeek 模型凭据 | 按需，亦可在后台配置 |
| `QWEN_API_KEY` | Qwen 模型凭据 | 按需，亦可在后台配置 |
| `ZHIPU_API_KEY` | 智谱模型凭据 | 按需，亦可在后台配置 |
| `GPT_IMAGE_API_KEY` / `GPT_IMAGE_BASE_URL` | 图片生成凭据与接口地址 | 使用图片生成时需要 |
| `API_PROXY_URL` | 模型服务的出站 HTTP(S) 代理 | 按需 |
| `API_REQUEST_TIMEOUT_MS` | 模型请求超时 | 否 |
| `EMBEDDING_MODEL` | 记忆向量模型，默认 `text-embedding-3-small` | 否 |
| `AUTO_MEMORY_MAX_PER_USER` | 每个用户允许保留的自动记忆数量 | 否 |
| `CLAWBOT_BASE_URL` | ClawBot 扫码服务地址 | 使用微信绑定时需要 |
| `START_WECHAT_WORKERS` | 是否在服务启动时恢复微信 Worker，默认 `true` | 否 |
| `OSS_*` | 阿里云 OSS 地域、Endpoint、Bucket 和凭据 | 可选；缺省时使用本地上传目录 |
| `SMTP_*` | SMTP 主机、端口、账号、密码和发件人 | 发送重置邮件与告警时需要 |
| `PASSWORD_RESET_URL` | 密码重置链接的前端入口 | 启用邮件重置时需要 |

模型 API Key 有两种配置方式：

- 在 `server/.env` 中设置，适合首次启动和基础开发。
- 在管理后台的“API Key”页面添加，适合运营管理、连通测试和多 Key 切换。

记忆 Embedding 依赖一个可用的 OpenAI 或兼容接口；缺少或调用失败时，聊天和记忆 CRUD 仍可工作，检索会使用词法相似度回退。

## API 概览

所有业务路由使用 `/api/v1` 前缀；除公开内容与鉴权接口外，均需携带 Bearer Token。

| 路径 | 方法 | 说明 |
|---|---|---|
| `/health` | `GET` | 服务健康检查 |
| `/api/v1/auth/register` | `POST` | 注册账号 |
| `/api/v1/auth/login` | `POST` | 用户名/邮箱登录 |
| `/api/v1/auth/refresh` | `POST` | 轮换 Refresh Token |
| `/api/v1/auth/forgot-password` | `POST` | 请求密码重置 |
| `/api/v1/conversations` | `GET` / `POST` | 查询或创建会话 |
| `/api/v1/conversations/:id` | `PATCH` / `DELETE` | 更新或删除会话 |
| `/api/v1/conversations/:id/messages` | `GET` | 获取历史消息 |
| `/api/v1/conversations/:id/messages/sync` | `POST` | 幂等同步离线消息 |
| `/api/v1/conversations/:id/chat` | `POST` | 发送消息并接收 SSE 回复 |
| `/api/v1/memories` | `GET` / `POST` | 查询或创建记忆 |
| `/api/v1/memories/:id` | `PATCH` / `DELETE` | 更新或删除记忆 |
| `/api/v1/personas` | `GET` / `POST` | 查询或创建人格 |
| `/api/v1/models` | `GET` | 获取当前用户可用模型 |
| `/api/v1/emojis/sync` | `GET` | 表情包增量同步 |
| `/api/v1/media/upload-url` | `POST` | 获取上传地址 |
| `/api/v1/user/profile` | `GET` / `PATCH` | 用户资料 |
| `/api/v1/user/settings` | `GET` / `PUT` | 云端用户设置 |
| `/api/v1/wechat/*` | 多种 | 微信扫码、状态、人格、重启与解绑 |
| `/api/v1/app/*` | `GET` | 版本、公告和法律文档 |
| `/api/v1/admin/*` | 多种 | 管理后台接口，仅管理员可用 |

聊天端点使用 `text/event-stream` 返回元数据、文本增量、情绪、动作、表情包和结束事件。调用方应逐事件解析，不要等待整个响应体完成。

## 测试与质量检查

在仓库根目录执行：

| 检查 | 命令 | 说明 |
|---|---|---|
| Android JVM 测试 | `.\gradlew.bat test` | 不需要设备 |
| Android Lint | `.\gradlew.bat lint` | 静态检查 Android 项目 |
| Android Debug 构建 | `.\gradlew.bat assembleDebug` | 生成调试 APK |
| Android 设备测试 | `.\gradlew.bat connectedAndroidTest` | 需要已连接设备或模拟器 |
| 服务端单元测试 | `npm --prefix server run test:unit` | 不依赖已启动的 API |
| 服务端完整测试 | `npm --prefix server test` | 需要 API、PostgreSQL 和 Redis 已启动 |
| 管理端单元测试 | `npm --prefix admin run test:unit` | 测试 URL 等基础逻辑 |
| 管理端生产构建 | `npm --prefix admin run build` | 同时验证 Vue/Vite 构建 |

macOS / Linux 请将 Android 命令中的 `.\gradlew.bat` 替换为 `./gradlew`。涉及 API Key、用户或同步的服务端集成测试会写入开发数据库，不要对生产数据库运行。

## 生产部署

完整操作参考 [`server/DEPLOY.md`](./server/DEPLOY.md) 和 [`docs/操作手册.md`](./docs/操作手册.md)。建议部署拓扑为：

1. PostgreSQL 与 Redis 仅监听本机或 Docker 私有网络。
2. Fastify 监听内部端口 `3000`，由进程管理器或容器负责守护。
3. 执行 `npm --prefix admin run build`，由 Nginx/Caddy 托管 `admin/dist`。
4. 反向代理 `/api/v1` 与 `/health` 到 Fastify，并为 SPA 配置 `index.html` 回退。
5. 对外只开放 80/443，正式访问统一使用 HTTPS。
6. 为 SSE 关闭代理缓冲，并将读取超时设置为至少 300 秒。

Nginx 核心配置示例：

```nginx
server {
    listen 443 ssl;
    server_name miao.example.com;

    root /opt/miao/admin/dist;
    index index.html;

    location /api/v1/ {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_buffering off;
        proxy_read_timeout 300s;
    }

    location /health {
        proxy_pass http://127.0.0.1:3000/health;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

生产发布前至少完成以下事项：

- 设置 `NODE_ENV=production` 并使用三个互不相同的随机安全密钥。
- 修改 Compose 中的开发数据库密码，限制 Redis 和 PostgreSQL 的访问范围。
- 设置正确的 `LOCAL_BASE_URL`、`CORS_ORIGINS` 和 Android `MIAO_API_BASE_URL`。
- 仅在受控反向代理后启用 `TRUST_PROXY=true`，并验证管理员 IP 白名单。
- 配置数据库与 `server/uploads/` 的定期备份；使用 OSS 时同时配置 Bucket 生命周期与权限。
- 不把 `.env`、`local.properties`、API Key、JWT 密钥、数据库备份或生成物提交到 Git。

## 安全说明

- 用户密码使用 bcrypt（cost 12）哈希保存。
- Access Token 默认有效期 2 小时；Refresh Token 默认有效期 30 天，数据库仅保存其 SHA-256 哈希并执行单次轮换。
- 模型 API Key 和微信 Token 使用基于 `DATA_ENCRYPTION_KEY` 的 AES-256-GCM 密文保存。
- 管理接口同时检查 JWT 中的管理员角色、数据库中的实时账号状态和可选 IP 白名单。
- 关键管理操作记录管理员、目标、请求 IP、时间和详情审计信息。
- Fastify 提供全局及鉴权端点的请求限流；公网部署仍建议叠加反向代理/WAF 限流。
- API 服务自身监听 HTTP；传输安全应由 Nginx、Caddy、云负载均衡等受控入口提供。

部署者需要自行审查模型服务、对象存储、邮件和微信通道的隐私政策与合规要求。不要把真实用户数据发送到未经确认的第三方接口。

如果发现安全问题，请不要在公开 Issue 中发布密钥、可利用细节或真实用户数据；若仓库已启用 GitHub Security Advisories，请优先使用私密报告功能。

## 常见问题

### Android 无法连接本地 API

- 模拟器使用 `10.0.2.2`，不是 `localhost`。
- 真机使用开发电脑的局域网 IP，并检查同一网络与系统防火墙。
- 确认地址包含 `/api/v1/`，修改 Gradle 属性后重新安装 App。
- 先在电脑上访问 `http://localhost:3000/health`，确认服务、PostgreSQL 和 Redis 均正常。

### 管理后台提示账号或密码错误

项目没有默认管理员密码。先注册普通用户，再按[创建首个管理员](#创建首个管理员)中的 SQL 提升角色。管理员仍使用注册时设置的密码。

### App 中没有可用模型

登录管理后台，添加并测试至少一个 API Key；随后在“模型管理”页面发现/启用模型。自定义中转地址必须填写服务商实际提供的 API 根地址。

### 没有配置 OpenAI Key，记忆还能使用吗

可以。记忆的增删改查和上下文注入仍可使用；缺少 Embedding 能力时会回退到词法匹配，语义召回质量可能下降。

### 不配置 OSS 可以上传图片吗

可以。服务端会把文件保存到 `server/uploads/` 并通过 `/api/v1/uploads/` 提供访问。生产环境需要将该目录放在持久化磁盘上并纳入备份；多实例部署推荐使用对象存储。

### 微信绑定是否需要在环境变量中填写 AppID

当前流程由 ClawBot 扫码获得通道凭据，不使用传统公众号 AppID/Secret。服务端仍需能访问 `CLAWBOT_BASE_URL`，并保持微信 Worker 运行。

## 参与贡献

欢迎提交 Issue 和 Pull Request。建议流程：

1. Fork 仓库并从最新主分支创建功能分支。
2. 保持修改聚焦，遵循 Kotlin 官方风格和现有 JavaScript ES Module 风格。
3. 新逻辑补充对应测试；UI 修改附上 Android 或管理后台截图/录屏。
4. 在提交前执行受影响模块的测试、Lint 或生产构建。
5. PR 中说明变更动机、验证结果、配置/迁移影响和关联 Issue。

问题反馈：<https://github.com/hyxhyxhyx153-cloud/Miao/issues>

## 路线图

以下方向仍处于规划阶段，实际顺序可能调整：

- 会员与订阅体系。
- 会话导出为 PDF/TXT。
- 更多语音交互与角色表现形式。
- Android 桌面小组件。
- 记忆知识图谱与可视化。

## 许可证

本项目采用 [MIT License](./LICENSE) 开源。

你可以自由使用、复制、修改、合并、发布、分发、再许可和销售本软件，但必须在软件副本中保留原版权声明与许可证声明。软件按“原样”提供，不附带任何明示或默示担保。

Copyright © 2026 hyxhyxhyx153-cloud
