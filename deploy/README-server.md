# VideoNest 服务器部署

当前脚本按单台 Ubuntu 服务器部署，默认目标为 `ubuntu@82.157.205.6:/opt/videonest`。本机负责编译 Java Jar 和前端 dist，服务器只把这两个本地产物复制进运行镜像并启动 Docker Compose。

## 1. 服务器准备

服务器需要安装 Docker Engine、Docker Compose 插件和 `curl`，并确保当前 SSH 用户可以执行 `sudo docker`。

服务器必须保留之前已经安装 FFmpeg 的 `videonest-backend:latest` 镜像。部署脚本会先检查该镜像，只复用它作为 Java 运行环境，不会执行 `apk add ffmpeg`，也不会在服务器重新下载 FFmpeg。可以先检查：

```bash
sudo docker image inspect videonest-backend:latest
```

云服务器安全组只需开放：

- `22/tcp`：SSH；
- `80/tcp`：网站；
- `9000/tcp`：视频和封面对象访问。

不要向公网开放 `3306`、`6379`、`5672`、`15672`、`9001`、`8080`。Compose 已把这些管理端口绑定到服务器的 `127.0.0.1`。

## 2. 本机配置

项目根目录 `.env` 必须填写真实密码。可以参考 `deploy/server.env.example`。部署脚本会读取其中的密钥，但会在临时副本中自动把网站地址、MinIO 公网地址和 Flyway 基线改成服务器值；不会修改本机 `.env`。

首次正式部署前建议把示例中的短密码换成随机强密码。`JWT_SECRET` 至少 32 个字符，投入使用后不要随意更换。

## 3. 执行部署

在项目根目录打开 PowerShell：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\deploy.ps1 `
  -Server "82.157.205.6" `
  -RemoteUser "ubuntu" `
  -IdentityFile "C:\你的密钥目录\server.pem"
```

如果 SSH 使用密码登录，省略 `-IdentityFile`。非默认 SSH 端口可增加 `-SshPort 端口号`。

脚本会依次执行测试、本机打包、上传、服务器数据库备份、后端/前端产物封装、容器更新和健康检查。它不会重新构建 RabbitMQ，也不会下载 FFmpeg。数据库结构由 Flyway 自动迁移，不再手工执行 `sql` 目录中的脚本。已有 MySQL、MinIO、Redis 和 RabbitMQ 命名卷不会被删除。

旧数据库如果留下 V5 媒体字段迁移的失败记录，脚本会在备份成功后逐一补齐缺失字段；确认三个字段完整后才修复该条 Flyway 历史。不会删除视频或其他业务数据，也不会修改已经发布的 V5 文件及其校验值。

如果旧数据库还留下 V6 评论根节点迁移的失败记录，脚本会清理迁移遗留的临时存储过程，补齐两个字段和索引，并按原迁移规则回填旧回复的根评论编号；确认结构完整后才修复 V6 历史，不删除评论。

部署还会检查 V7 的 Outbox 表及索引；如果存在 V7 失败记录，会保留已有事件并补建缺失的整张表。启动前必须确保 V5～V7 不再有失败记录，避免逐个版本反复部署。

调试时可使用 `-SkipTests`；只有明确不需要部署前数据库备份时才使用 `-SkipBackup`。`-SkipBuild` 会复用项目根目录已有的 `videonest-deploy.tar.gz`，不要在改过代码后使用。

部署完成后访问：

- 网站：`http://82.157.205.6`
- MinIO 媒体入口：`http://82.157.205.6:9000`

本机数据库和本机 MinIO 数据不会自动上传到服务器。服务器会保留自己的业务数据。

## 4. 查看状态和日志

```bash
cd /opt/videonest
sudo docker compose -f docker-compose.yml -f docker-compose.jar.yml ps
sudo docker compose -f docker-compose.yml -f docker-compose.jar.yml logs -f --tail=200 backend frontend
```

部署前的数据库备份位于 `/opt/videonest/backups/pre-deploy-时间.sql`。

## 5. 域名与 HTTPS

当前参数适合 IP + HTTP。以后启用域名和 HTTPS 时，需要同时让网站和 MinIO 使用 HTTPS，否则浏览器会拦截页面中的 HTTP 视频。届时执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\deploy.ps1 `
  -Server "82.157.205.6" `
  -RemoteUser "ubuntu" `
  -PublicSiteUrl "https://你的域名" `
  -MinioPublicEndpoint "https://媒体域名"
```

HTTPS 还需要在服务器前面配置证书和反向代理，这部分不由当前 Compose 自动申请。
