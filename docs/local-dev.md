# 本地开发 / 本地部署

本地有两种完全不同的循环，别混着用：

| 场景 | 命令 | 改代码后 | 速度 |
| --- | --- | --- | --- |
| 日常写代码（热更新） | `-Action dev` | 前端 HMR 即时生效，后端重启当前进程 | 秒级 |
| 只起中间件、后端在 IDEA 里跑 | `-Action infra` | 用 IDE 的 Debug / HotSwap | 秒级 |
| 验证生产形态（镜像 + 容器） | `-Action app` | 必须重跑一次 `-Action app` | 30～60 秒 |

写在 `scripts/local.ps1` 里，统一入口；根目录还有个 `local.cmd` 薄包装，参数原样透传：

```powershell
.\local.cmd                 # == -Action app（本地部署）
.\local.cmd dev             # == -Action dev（日常调试）
.\local.cmd infra           # == -Action infra
.\local.cmd status|logs|down|clean
.\local.cmd -SkipBuild      # 本地部署，但不重新构建 jar/dist
```

下面都省略前半段，只写 `-Action xxx`。

---

## 一、日常调试（推荐）

```powershell
.\scripts\local.ps1 -Action dev
```

它会依次做三件事：

1. `docker compose up -d mysql redis rabbitmq minio minio-init`，并等到 mysql / redis / rabbitmq 都是 healthy；
2. 新开一个 PowerShell 窗口跑后端：`scripts/start-backend.ps1` → 读根目录 `.env` → `mvn spring-boot:run`（profile `dev`，监听 8080）；
3. 再新开一个窗口跑前端：`npm run dev`（vite，监听 5173，`/api` 代理到 8080）。

浏览器打开 **http://127.0.0.1:5173** 即可。改 `.vue`/`.ts` 是热更新；改 Java 需要重启后端窗口（IDEA 里可以开 `spring-boot:run` 的 Debug 用 HotSwap）。

注意：后端跑在宿主机上，**上传视频要用本机 PATH 里的 `ffmpeg` / `ffprobe`**（镜像里自带，宿主机需要自己装，或用 `FFMPEG_PATH` 指到本地可执行文件）。

## 二、只起中间件（后端放 IDE 里跑）

```powershell
.\scripts\local.ps1 -Action infra
```

只启动 mysql / redis / rabbitmq / minio，打印端口；然后你在 IDEA 里直接跑 `VideoNestApplication`（profile `dev`），或者手动：

```powershell
cd backend
mvn spring-boot:run
```

前端自己 `cd frontend; npm install; npm run dev`。

数据库连接信息来自根目录 `.env`（`application-dev.yml` 会自动导入）：`127.0.0.1:3307`，库名/账号密码都在 `.env` 里；Redis `127.0.0.1:6380`；MinIO `http://127.0.0.1:9010`。

## 三、本地全栈（验证生产形态）

```powershell
.\scripts\local.ps1 -Action app
```

这条和服务器部署走的是同一套产物模式：

1. 本机 `mvn package` + `npm run build`；
2. 首次会构建 `videonest-backend-base:latest`（JRE + ffmpeg，1～3 分钟，之后复用）；
3. 用 `Dockerfile.jar` / `Dockerfile.dist` 只 `COPY` 产物，构建镜像（几秒）；
4. 起 `videonest-backend` + `videonest-frontend` 容器，等健康检查。

访问 **http://127.0.0.1:8082**（脚本默认把 `HTTP_PORT` 设成 8082，避免和本机 80 端口冲突；想换端口就自己先设 `$env:HTTP_PORT`）。

产物没变、只想重建容器时加 `-SkipBuild`：

```powershell
.\scripts\local.ps1 -Action app -SkipBuild
```

## 四、其它命令

```powershell
.\scripts\local.ps1 -Action status          # docker compose ps
.\scripts\local.ps1 -Action logs            # 跟全部日志
.\scripts\local.ps1 -Action logs -Service backend
.\scripts\local.ps1 -Action down            # 停容器，保留数据卷
.\scripts\local.ps1 -Action clean           # 停容器 + 删数据卷（要输入 YES）
```

## 端口一览（本地）

| 服务 | 地址 |
| --- | --- |
| 前端（dev / vite） | http://127.0.0.1:5173 |
| 后端（dev / spring-boot:run） | http://127.0.0.1:8080 |
| 前端（`-Action app` 容器） | http://127.0.0.1:8082 |
| MySQL | 127.0.0.1:3307 |
| Redis | 127.0.0.1:6380 |
| RabbitMQ | 127.0.0.1:5672，管理台 15672 |
| MinIO | 127.0.0.1:9010，控制台 9011 |

## 首次运行会下载 / 构建什么

- 中间件镜像：mysql 8.4、redis 7.4、rabbitmq 4.2、minio，合计约 1.2GB（只下一次）。
- `videonest-backend-base:latest`：首次 `-Action app` 时构建，JRE + ffmpeg，1～3 分钟。
- 前端 `node_modules`：`npm ci`（走 npmmirror），约 1 分钟。
- 后端 Maven 依赖：你本机仓库是 `B:\Jar`，一般已经缓存。

## 常见问题

- **Docker 没启动**：脚本第一行就会报 `Docker is not reachable`，先开 Docker Desktop。
- **3307 / 6380 / 8080 / 8082 被占用**：本机可能已有 MySQL 或别的服务；改 `docker-compose.yml` 里的端口映射，或停掉占用进程（`netstat -ano | findstr :8080`）。
- **注册/上传报 403 `Invalid CORS request`**：本地 compose 已经内置放行 `5173`、`8082`、`80`；如果你换了端口，改 `.env` 里的 `CORS_ALLOWED_ORIGINS`。
- **`-Action app` 起来后改代码没效果**：这是生产形态，镜像里的 jar/dist 是构建时固定的，要重跑 `-Action app`（不加 `-SkipBuild`）。
- **上传视频失败、日志里找不到 ffmpeg**：`-Action dev` 是宿主机跑后端，需要本机装 ffmpeg；`-Action app` 用的是镜像里自带的 ffmpeg，不需要。
- **想彻底重置本地数据**：`-Action clean`（会删掉 mysql/redis/rabbitmq/minio 四个卷，输入 YES 确认）。
