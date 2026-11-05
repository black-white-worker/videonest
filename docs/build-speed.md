# 构建/部署为什么慢，以及现在怎么部署

## 结论

慢的不是「网络」这一项，而是**在只有 2 核 / 1.8GB 内存的服务器上跑完整的 Maven + npm 编译**。
把编译搬回本机、服务器只 `COPY` 产物之后，一轮部署从 **约 21 分钟** 降到 **24 秒**（2026-11-04 实测）。

## 实测证据

镜像层的 `CreatedAt` 就是每一步的完成时间（`docker history` 可查）：

| 时间 | 事件 |
| --- | --- |
| 22:06:13 | `vn-app-rabbitmq` 构建完成 |
| 22:12:14 | 后端镜像里 `apk add ffmpeg` 那层完成（131MB 解包） |
| 22:18:01 | `vn-app-vn-backend` 构建完成 → **后端约 12 分钟** |
| 22:20:47 | 前端镜像 `COPY nginx.conf`（运行阶段） |
| 22:27:07 | `vn-app-vn-frontend` 构建完成 → **前端约 9 分钟，其中 npm ci + vue-tsc + vite 约 6 分 20 秒** |

服务器配置：**2 vCPU、1.8GB 内存 + 2GB swap**。Maven 编译 Spring Boot 4 应用时堆内存吃紧，
一旦用到 swap，构建时间会被放大好几倍；npm 的 `vue-tsc -b` 同样是单线程重活。

## 三个根因

1. **编译位置错了**：`scripts/deploy.ps1` 把整份源码传给服务器，让 Docker 在 2 核机器上
   跑 `mvn dependency:go-offline` + `mvn package` + `npm ci` + `vite build`。
   本机 8 核做同样的事只要 1～2 分钟。
2. **基础镜像没预热**：`pull-images.sh`（在 learningplatform 仓库里）拉的是别的项目的清单
   （`golang:1.27-alpine`、`flyway`、`maven:3.9-amazoncorretto-21`、`mwader/static-ffmpeg`），
   videonest 真正需要的 `maven:3.9.11-eclipse-temurin-21-alpine`、`eclipse-temurin:21-jre-alpine`、
   `node:24-alpine`、`nginx:1.29-alpine` 一个都不在里面。
   结果每次构建要么现拉，要么直接用不上。
3. **预热脚本的失败重试是纯等待**：`mwader/static-ffmpeg`、`minio/mc` 被镜像站白名单拒绝，
   脚本仍会对每个镜像依次尝试 3 个镜像站 × 300 秒超时，几分钟全花在等超时上。

## 现在的流程（产物模式）

```
本机：mvn package + npm run build
      ↓ 只传 ~80MB（jar 79MB + dist 1.6MB + 几个配置文件）
服务器：docker compose build vn-backend vn-frontend   ← 只 COPY，缓存命中时 2 秒
      ↓
      重启两个容器 + 等健康检查（约 20 秒）
```

## 实测结果（2026-11-04 23:55，同一台服务器）

| 步骤 | 耗时 |
| --- | --- |
| `videonest-backend-base` 首次构建（JRE + ffmpeg，一次性） | 68s |
| 后端镜像构建（jar 命中缓存） | 0s |
| 前端镜像构建（dist 命中缓存） | 0s |
| 两个镜像构建合计 | **2s** |
| 容器重建 + 健康检查 | 22s |
| 一轮部署合计 | **24s** |

首次传 78.7MB 的 jar 时，构建上下文传输多花约 0.7 秒（内网/公网差异主要在 scp 那一步）。

关键文件：

| 文件 | 作用 |
| --- | --- |
| `backend/Dockerfile.base` | 运行时基础镜像（JRE + ffmpeg），**只构建一次**，之后一直复用 |
| `backend/Dockerfile.jar` | 在 base 上只 `COPY target/*.jar`，秒级出镜像 |
| `frontend/Dockerfile.dist` | 在 nginx 上只 `COPY dist/`，秒级出镜像 |
| `docker-compose.server.yml` | 默认用上面两个 Dockerfile（产物模式） |
| `docker-compose.source.yml` | 应急用：叠加后才回到「服务器全量编译」的旧行为 |
| `deploy/server/redeploy.sh` | 服务器端：确保 base → build 两个镜像 → up → 等健康 |
| `scripts/deploy.ps1` | 本机编译 + 打包 + 上传 + 调 redeploy.sh |
| `scripts/prefetch-images.sh` | 按 videonest 真实用到的镜像预热，240 秒超时、失败不重试 |

`.dockerignore` 也补齐了：`backend/Dockerfile.jar.dockerignore` 把上下文压到只剩 `target/*.jar`，
`frontend/Dockerfile.dist.dockerignore` 只剩 `dist` + `nginx.conf`，不会再出现把 `node_modules`
（几百 MB）发给 docker 的情况。

## 第一次使用（一次性准备）

1. 服务器上先跑一次预热（可选，但能省掉首次构建的拉取时间）：

   ```bash
   cd /opt/apps/videonest && bash scripts/prefetch-images.sh
   ```

2. 本机正常部署即可，`redeploy.sh` 会在首次自动构建 `videonest-backend-base:latest`
   （JRE + ffmpeg，约 1～3 分钟，之后永久复用）：

   ```powershell
   .\deploy.cmd
   ```

## 日常部署

```powershell
.\deploy.cmd                      # 编译 + 上传 + 重建 + 重启
.\deploy.cmd -SkipBuild           # 产物没变，只重传/重建
.\deploy.cmd -PublicSiteUrl "https://域名"   # 换前端站点地址时
```

后端默认跳过测试（和以前服务器上 `mvn -DskipTests` 的行为一致）。要跑测试：

```powershell
.\deploy.cmd -RunTests
```

注意：集成测试用 Testcontainers，本机需要 Docker 在跑。

## 应急：必须在服务器上全量编译

```bash
cd /opt/apps/videonest
docker compose -f docker-compose.server.yml -f docker-compose.source.yml \
  --env-file .env.prod build
```

这一条仍然是 20 分钟级别，只在没有本机产物时用。

## 回退

想完全回到改造前，只需把 `docker-compose.server.yml` 里 `vn-backend` / `vn-frontend`
的 `dockerfile:` 两行删掉（以及 `args`），并把 `scripts/deploy.ps1` 还原成上传源码的版本。
`Dockerfile`、`Dockerfile.base`、`Dockerfile.dist` 都还在，两条路互不影响。
