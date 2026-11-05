# VideoNest 服务器部署

> 本文已按当前「产物模式」重写。为什么要这样改、实测数据是多少，见 [docs/build-speed.md](../docs/build-speed.md)。
> 旧版单机布局（`82.157.205.6:/opt/videonest`、复用 `videonest-backend:latest`）已经不再使用。

## 当前布局

- 共享中间件（MySQL / Redis(db1) / MinIO）由 lp-infra 提供，不在本项目 Compose 里。
- 本项目目录：服务器 `/opt/apps/videonest`，Compose 文件 `docker-compose.server.yml`，项目名 `vn-app`。
- 三个容器：`vn-backend`、`vn-frontend`、`vn-rabbitmq`；对外端口 `8082`（`HTTP_PORT` 可改）。

## 日常部署

在项目根目录：

```powershell
.\deploy.cmd
```

它会：本机 `mvn package` → 本机 `npm run build` → 打包上传（约 80MB）→ 服务器
`deploy/server/redeploy.sh`：确保 `videonest-backend-base:latest` → build 两个镜像（只 COPY）→ 重启 → 等健康检查。

常用参数：

```powershell
.\deploy.cmd -SkipBuild                      # 产物没变，只上传/重建
.\deploy.cmd -SkipUpload                     # 不重传，用服务器上已有的产物重建
.\deploy.cmd -RunTests                       # 部署前跑后端测试（需要本机 Docker）
.\deploy.cmd -PublicSiteUrl "https://域名"    # 换前端站点地址
.\deploy.cmd -Server 1.2.3.4 -IdentityFile C:\keys\server.pem
```

## 服务器准备（一次性）

0. **共享中间件必须先起来**。MySQL/Redis/MinIO 由 lp-infra 提供，不在本项目的 Compose 里。
   没起的话 `vn-backend` 会报 `UnknownHostException: mysql`，健康检查失败，
   `docker compose up` 会以 `dependency failed to start` 中止：

   ```bash
   cd /opt/apps/learningplatform/deploy/server && ./deploy.sh infra
   ```

   确认方式：`docker ps --format '{{.Names}}' | grep infra-mysql`。
   首次启动会创建 `lp-infra` 数据卷并执行 `init/mysql/01-databases.sql`（建 `videonest` 空库），
   Flyway 随后自动建表；**旧服务器上的业务数据不会自动过来，需要自行导入 dump**。

1. Docker Engine + Compose v2，且当前 SSH 用户能直接执行 `docker`（不用 sudo）。
2. `/opt/apps/videonest/.env.prod` 必须存在，部署脚本不会上传它。
   其中 `MYSQL_ROOT_PASSWORD` 必须与 lp-infra 的 `.env` 一致（当前两台配置一致）。
3. 预热基础镜像（可选但强烈建议，避免首次构建现拉）：

   ```bash
   cd /opt/apps/videonest && bash scripts/prefetch-images.sh
   ```

4. 首次部署时 `redeploy.sh` 会自动构建 `videonest-backend-base:latest`（JRE + ffmpeg，
   约 1～3 分钟），之后每次部署都复用它，不会再碰 Alpine/ffmpeg。

安全组需开放 `22/tcp`、`8082/tcp` 和 `9010/tcp`。VideoNest 用浏览器直连 MinIO：后端
签发的上传 URL 指向 `http://<PUBLIC_HOST>:9010`，视频和封面数据不会经过 8082 的 Nginx。
共享 MinIO 必须发布 `9010:9000` 且允许 VideoNest 来源的 S3 CORS（PUT、ETag）；当前
`learningplatform` 的 infra Compose 映射了 9010 并将 MinIO CORS 设为 `*`。`3306`、`6379`、
`5672`、`15672`、`9001`、`8080` 在 Compose 里都绑到 `127.0.0.1`，不要对公网开放。

若 MinIO 的公网地址不同，可在 `.env.prod` 设置 `MINIO_PUBLIC_ENDPOINT`，例如
`http://101.201.208.223:9010`；该值必须与浏览器可访问的主机、端口一致。修改后重建后端。

## 手动操作（服务器上）

```bash
cd /opt/apps/videonest

bash deploy/server/redeploy.sh build      # 只重建镜像，不重启
bash deploy/server/redeploy.sh            # 重建 + 重启 + 等健康

docker compose -f docker-compose.server.yml --env-file .env.prod ps
docker compose -f docker-compose.server.yml --env-file .env.prod logs -f --tail=200 vn-backend vn-frontend
```

## 应急：必须在服务器上全量编译

没有本机产物、或要在服务器上验证源码树时：

```bash
cd /opt/apps/videonest
docker compose -f docker-compose.server.yml -f docker-compose.source.yml \
  --env-file .env.prod build
```

2 vCPU / 1.8GB 上这一步约 20 分钟（mvn + npm ci + vite），正常部署不要走这条路。

## 数据库迁移

Flyway 在应用启动时自动迁移，不需要手工执行 `sql/` 里的脚本。
`FLYWAY_BASELINE_VERSION` 默认 3；历史失败版本（V5/V6/V7）的处理方式见 git 历史里的旧文档。

空库首次启动会从 V1 一路迁到最新（2026-11-04 实测 10 个版本、13 张表、1.1 秒）。

## 故障排查

| 现象 | 原因 / 处理 |
| --- | --- |
| `dependency failed to start: container vn-backend is unhealthy` | 共享中间件没起，后端 `UnknownHostException: mysql`。执行 `cd /opt/apps/learningplatform/deploy/server && ./deploy.sh infra`，再用 `-SkipBuild -SkipUpload` 重跑部署。 |
| `./deploy.sh infra` 报 `network lp-shared was found but has incorrect label com.docker.compose.network` | 这台机器上的 `lp-shared` 是手工建的、没有 compose 标签。先停应用容器：`cd /opt/apps/videonest && docker compose -f docker-compose.server.yml --env-file .env.prod stop vn-backend vn-frontend`，再 `docker network rm lp-shared`，然后重跑 `./deploy.sh infra`。 |
| 后端起来了但页面空的 | 这是首次初始化的空库。旧的业务数据不会自动迁移，需要把旧库 dump 导入 `videonest` 库后重启后端。 |
| 页面能浏览，但注册/登录/上传等 POST 返回 403 `Invalid CORS request` | 后端 `app.cors.allowed-origins` 不含站点 Origin。Compose 已用 `CORS_ALLOWED_ORIGINS=…,${PUBLIC_SITE_URL}` 注入；换域名或 IP 时改 `.env.prod` 的 `PUBLIC_SITE_URL`（或显式设 `CORS_ALLOWED_ORIGINS`），再重建后端即可。 |
| `up -d` 报 `port is already allocated` | 8082 被别的进程占用，改 `.env.prod` 里的 `HTTP_PORT`。 |
| 上传停在“正在准备上传”或对象存储请求失败 | 浏览器开发者工具 Network 查看 `/api/files/presign` 或 `/api/files/multipart/sessions` 状态；若后端成功，再检查浏览器能否访问 `http://<PUBLIC_HOST>:9010`、安全组/防火墙是否开放 9010，以及 MinIO CORS 是否允许站点 Origin 的 PUT 并暴露 ETag。 |
