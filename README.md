# VideoNest

VideoNest 是一个前后端分离的视频社区项目，提供视频投稿、异步转码、内容审核、播放互动、评论回复、关注及站内通知等能力。

前端使用 Vue 3 + TypeScript + Vite，后端使用 Spring Boot + MyBatis-Plus，并集成 MySQL、Redis、MinIO 与 RabbitMQ。

## 功能概览

- 用户注册、登录与 JWT 身份认证；支持普通用户和管理员角色。
- 视频首页、分类筛选、关键词搜索、分页列表与 Redis 热门榜单。
- 视频播放及 480P、720P、1080P 多清晰度资源。
- 点赞、收藏、评论、二级回复及评论删除。
- 关注/取关作者，查看关注与粉丝列表。
- 视频与封面上传，创作者投稿、编辑、删除及投稿状态查看。
- RabbitMQ 异步视频处理：FFmpeg 转码、自动生成封面、处理失败记录。
- 管理员审核投稿、编辑或删除视频、管理和恢复评论。
- 关注、评论、回复触发站内通知；支持未读数和标记已读。

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 前端 | Vue 3、TypeScript、Vite、Axios、Element Plus |
| 后端 | Java 21、Spring Boot 4、Spring Security、JWT、MyBatis-Plus |
| 数据库 | MySQL |
| 缓存 | Redis 7 |
| 对象存储 | MinIO |
| 消息队列 | RabbitMQ 4 Management |
| 视频处理 | FFmpeg |
| 容器化 | Docker Compose |

## 项目结构

```text
videonest/
├─ backend/                 # Spring Boot 后端
│  ├─ src/main/java/         # 按 auth、video、interaction、follow 等业务模块组织
│  ├─ src/main/resources/    # 应用配置与 MyBatis XML
│  └─ pom.xml                # Maven 依赖与构建配置
├─ frontend/                # Vue 3 前端
│  └─ src/
│     ├─ api/                # 后端接口封装
│     ├─ views/              # 首页、视频详情、投稿、个人中心、后台等页面
│     └─ router/             # 前端路由
├─ deploy/                  # Redis、MinIO、RabbitMQ 的 Docker Compose 文件
├─ sql/                     # 数据库初始化和增量迁移脚本
└─ README.md
```

## 环境要求

- JDK 21
- Maven 3.9+（或使用仓库内的 Maven Wrapper）
- Node.js 20+
- MySQL 8+
- Docker Desktop / Docker Compose
- FFmpeg（用于异步转码与自动截帧）

## 本地启动

### 1. 初始化 MySQL

创建数据库 `videonest`，并执行以下脚本：

```text
sql/SQL.sql
sql/2026-07-27-add-video-comment-deleted-at.sql
```

### 2. 启动基础服务

分别在以下目录执行命令：

```powershell
cd deploy/redis
docker compose -f docker-compose.redis.yml up -d

cd ../minio
docker compose up -d

cd ../rabbitmq
docker compose up -d
```

默认访问地址：

| 服务 | 地址 |
| --- | --- |
| MinIO API | `http://127.0.0.1:9000` |
| MinIO Console | `http://127.0.0.1:9001` |
| RabbitMQ Console | `http://127.0.0.1:15672` |
| Redis | `127.0.0.1:6379` |

MinIO、Redis 和 RabbitMQ 的开发环境账号配置位于 `backend/src/main/resources/application.yml` 及对应 Compose 文件中。

### 3. 配置后端

检查并按本机环境修改 [backend/src/main/resources/application.yml](backend/src/main/resources/application.yml)：

- MySQL 数据库地址、账号和密码；
- Redis、MinIO、RabbitMQ 连接信息；
- `video-process.ffmpeg-path`，设置为本机 `ffmpeg.exe` 的绝对路径。

首次启动 MinIO 时，需要确保已创建配置中指定的 Bucket：`videonest`。

### 4. 启动后端

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

后端默认地址：`http://127.0.0.1:8080`。

如需将 Maven 依赖缓存限制在项目目录，可使用：

```powershell
.\mvnw.cmd "-Dmaven.repo.local=..\.m2" spring-boot:run
```

### 5. 启动前端

```powershell
cd frontend
npm install
npm install axios element-plus
npm run dev
```

前端默认地址：`http://127.0.0.1:5173`。开发环境下，Vite 会将 `/api` 请求代理到后端 `http://localhost:8080`。

> 当前前端源码已使用 `axios` 和 `element-plus`，但这两个依赖尚未登记在 `frontend/package.json`；首次在新环境启动时需要执行上面的安装命令。后续建议将安装结果提交到 `frontend/package.json` 和 `frontend/package-lock.json`。

## 常用命令

```powershell
# 前端构建
cd frontend
npm run build

# 后端测试
cd backend
.\mvnw.cmd test

# 后端打包
.\mvnw.cmd package
```

## 核心接口分组

| 模块 | 接口前缀 |
| --- | --- |
| 认证 | `/api/auth` |
| 视频与热门榜 | `/api/videos` |
| 分类 | `/api/categories` |
| 文件上传 | `/api/files` |
| 创作者中心 | `/api/creator` |
| 关注关系 | `/api/users` |
| 通知中心 | `/api/notifications` |
| 管理端视频 | `/api/admin/videos` |
| 管理端评论 | `/api/admin/comments` |

## 开发说明

- 视频状态包括：`PROCESSING`、`PROCESS_FAILED`、`PENDING`、`PUBLISHED`、`REJECTED`。
- 视频详情会增加播放数并参与热门榜计算；点赞、评论、收藏也会影响热度。
- 视频文件与封面存储在 MinIO 中，后端对外返回可访问的临时 URL。
- 评论使用两级结构：顶级评论的 `parentId` 为 `0`，回复关联对应顶级评论。
- Snowflake 评论 ID 在前端按字符串处理，避免 JavaScript 整数精度丢失。

## 注意事项

- `backend/target`、`frontend/dist`、各级 `node_modules` 和本地 `.m2` 都是构建或依赖缓存，不属于业务源码。
- `application.yml` 和 Docker Compose 内的账号密码仅适用于本地开发；部署到生产环境时请通过环境变量或密钥管理服务替换。
- 修改 Controller 后若接口仍然返回旧路由错误，请确认实际运行的 Spring Boot 进程已完全重启。
