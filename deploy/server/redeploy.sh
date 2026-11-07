#!/usr/bin/env bash
# =====================================================================
# 服务器端重建脚本（产物模式）
#
# 前提：本机已用 scripts/deploy.ps1 上传过
#   backend/target/app.jar   （本机 mvn package 的产物）
#   frontend/dist            （本机 npm run build 的产物）
#
# 这里只做三件事：
#   1) 确保运行基础镜像 videonest-backend-base:latest 存在（JRE + ffmpeg，只建一次）
#   2) docker compose build vn-backend vn-frontend —— 只 COPY 产物，通常 10~30 秒
#   3) 重启两个容器并等健康检查
#
# 用法：
#   bash deploy/server/redeploy.sh          # 构建 + 备份 + 重启 + 等健康（演示档）
#   bash deploy/server/redeploy.sh build    # 只构建，不备份不重启
#   PROFILE=bench bash deploy/server/redeploy.sh   # 带压测叠层重建（测完记得不带 PROFILE 再跑一次）
#
# 备份/闸门相关：
#   SKIP_DB_BACKUP=1   跳过 mysqldump（只在明知库是空的、或刚还原过的时候用）
#   BACKUP_DIR         备份目录，默认 /var/backups/videonest/mysql（刻意放在仓库之外，
#                      免得 dump 落在 git 工作区里被下次上传脚本当成产物带上去）
#   MIN_FREE_MB        重启前要求的可用内存（默认 220）
#   MIN_FREE_DISK_MB   重启前要求的 Docker 数据盘可用空间（默认 1536）
# =====================================================================
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

ENV_FILE="${ENV_FILE:-.env.prod}"
PROFILE="${PROFILE:-idle}"
BASE_IMAGE="videonest-backend-base:latest"
MODE="${1:-up}"

die() { echo "[x] $*" >&2; exit 1; }

COMPOSE=(docker compose -f docker-compose.server.yml --env-file "$ENV_FILE")
# 压测叠层只多改 vn-backend 一段，其他服务照旧
if [ "$PROFILE" = "bench" ]; then
  [ -f docker-compose.bench.yml ] || die "PROFILE=bench 但 docker-compose.bench.yml 不存在"
  COMPOSE=(docker compose -f docker-compose.server.yml -f docker-compose.bench.yml --env-file "$ENV_FILE")
fi

# 只删 <none>:<none> 悬空镜像；仍被任何容器引用的镜像不会被删，失败也不影响部署结果。
prune_images() {
  echo "==> 清理悬空镜像（docker image prune -f）"
  docker image prune -f || true
}

# 从 env 文件里取一个变量，去掉外层引号；不 source 整个文件，
# 因为 .env.prod 里可能有带 $ 的密码，source 会把它当变量展开。
env_from_file() {
  local key="$1" line val
  line=$(grep -E "^[[:space:]]*${key}=" "$ENV_FILE" | tail -n 1 || true)
  [ -n "$line" ] || return 0
  val="${line#*=}"
  val="${val%$'\r'}"
  case "$val" in
    \"*\") val="${val#\"}"; val="${val%\"}" ;;
    \'*\') val="${val#\'}"; val="${val%\'}" ;;
  esac
  printf '%s' "$val"
}

# 最近一次备份的路径；没有备份时给出可操作提示，避免打印空字符串
latest_backup() {
  local latest
  latest=$(ls -1 "${BACKUP_DIR:-/var/backups/videonest/mysql}"/videonest-*.sql.gz 2>/dev/null | tail -n 1)
  if [ -n "$latest" ]; then
    printf '%s\n  还原：gunzip -c %s | docker exec -i -e MYSQL_PWD=... infra-mysql mysql -uroot %s\n' \
      "$latest" "$latest" "${DB_NAME:-videonest}"
  else
    printf '（本次没有备份，BACKUP_DIR=%s 下没有 dump 文件）\n' "${BACKUP_DIR:-/var/backups/videonest/mysql}"
  fi
}

[ -f "$ENV_FILE" ] || die "$ENV_FILE 不存在（先 cp .env.prod.example .env.prod 再填）"
[ -f backend/target/app.jar ] || die "backend/target/app.jar 不存在，请在本机先跑 scripts/deploy.ps1"
[ -d frontend/dist ] || die "frontend/dist 不存在，请在本机先跑 scripts/deploy.ps1"

# ---- 0) 闸门：内存与磁盘 ----
# 2G 的机器上三个栈的 mem_limit 加起来是超卖的，靠 zram + swap 兜着。
# 真正会翻车的是"在内存已经见底时重建容器"：新 JVM 起来那一刻最吃内存，
# OOM killer 挑中的往往是 MySQL。所以先量一下，不够就别动手。
MIN_FREE_MB="${MIN_FREE_MB:-220}"
MIN_FREE_DISK_MB="${MIN_FREE_DISK_MB:-1536}"
if [ "$MODE" != "build" ]; then
  AVAIL_MB=$(awk '/^MemAvailable:/{print int($2/1024)}' /proc/meminfo 2>/dev/null || echo "")
  if [ -z "$AVAIL_MB" ]; then
    echo "[!] 读不到 /proc/meminfo，跳过内存闸门（只在这台机器不是 Linux 时才可能）" >&2
  else
    echo "==> 资源闸门：可用内存 ${AVAIL_MB}M（要求 >= ${MIN_FREE_MB}M）"
    # free 只是给人看的补充信息，缺失时不能让它把脚本带崩（set -e + 管道）
    command -v free >/dev/null 2>&1 && free -m | awk 'NR<=3{print "    "$0}' || true
    if [ "$AVAIL_MB" -lt "$MIN_FREE_MB" ]; then
      echo
      echo "[x] 可用内存只有 ${AVAIL_MB}M，重建 vn-backend 有把邻居（infra-mysql）挤进 OOM 的风险。" >&2
      echo "    先降载再来：docker stop lp-backend hp-backend hp-frontend lp-frontend" >&2
      echo "    或者确认无风险后跳过：MIN_FREE_MB=0 bash $0" >&2
      exit 1
    fi
  fi

  DOCKER_ROOT=$(docker info -f '{{.DockerRootDir}}' 2>/dev/null || echo /var/lib/docker)
  FREE_DISK_MB=$(df -m --output=avail "$DOCKER_ROOT" 2>/dev/null | tail -n 1 | tr -d ' ')
  if [ -n "$FREE_DISK_MB" ] && [ "$FREE_DISK_MB" -lt "$MIN_FREE_DISK_MB" ]; then
    # 不够就先清一次悬空镜像再重新量：这个项目每次部署都会把一个旧镜像变成 <none>，
    # 79M 的 jar 叠上去，盘就是这么满的。清完够就继续，不用人工介入。
    echo "==> ${DOCKER_ROOT} 可用磁盘 ${FREE_DISK_MB}M 偏低（要求 >= ${MIN_FREE_DISK_MB}M），先清悬空镜像"
    prune_images
    FREE_DISK_MB=$(df -m --output=avail "$DOCKER_ROOT" 2>/dev/null | tail -n 1 | tr -d ' ')
  fi
  if [ -n "$FREE_DISK_MB" ]; then
    echo "==> ${DOCKER_ROOT} 可用磁盘 ${FREE_DISK_MB}M（要求 >= ${MIN_FREE_DISK_MB}M）"
    if [ "$FREE_DISK_MB" -lt "$MIN_FREE_DISK_MB" ]; then
      echo
      echo "[x] 清过悬空镜像还是只剩 ${FREE_DISK_MB}M。新镜像 79M jar + 旧镜像 dangling 层很快就把盘写满，" >&2
      echo "    写满之后 MySQL 和 MinIO 会一起挂。手工再清一层：" >&2
      echo "      docker builder prune -f   # 只清构建缓存，不动在用的镜像" >&2
      echo "      docker system df          # 看还占多少" >&2
      echo "      du -sh ${DOCKER_ROOT}/volumes  # 找大卷" >&2
      exit 1
    fi
  fi
fi

# ---- 1) 运行基础镜像（含 ffmpeg），只建一次 ----
if docker image inspect "$BASE_IMAGE" >/dev/null 2>&1; then
  echo "[base] $BASE_IMAGE 已存在，跳过（要重建先 docker rmi $BASE_IMAGE）"
else
  echo "[base] 首次构建 $BASE_IMAGE（JRE + ffmpeg，约 1~3 分钟，只做一次）"
  docker build -f backend/Dockerfile.base -t "$BASE_IMAGE" backend
fi

# ---- 2) 部署前备份业务库（只备份 videonest 一个库，保留最近 3 份）----
# 为什么必须有：这个脚本要用 Flyway 改 schema，而部署本身是不可回滚的——
# jar 换掉之后旧版本代码配不上新库结构，库要是被迁移写坏了，演示就当场结束。
# dump 走 --single-transaction，InnoDB 上是一致的快照，也不锁表；代价是
# 数据量越大越慢（当前库几百 MB 量级，十几秒），所以只在 up 模式做。
if [ "$MODE" != "build" ]; then
  if [ "${SKIP_DB_BACKUP:-0}" = "1" ]; then
    echo "==> 已按要求跳过部署前数据库备份（SKIP_DB_BACKUP=1）"
  else
    DB_NAME=$(env_from_file DB_NAME)
    DB_NAME="${DB_NAME:-videonest}"
    MYSQL_ROOT_PW=$(env_from_file MYSQL_ROOT_PASSWORD)
    BACKUP_DIR="${BACKUP_DIR:-/var/backups/videonest/mysql}"
    if [ -z "$MYSQL_ROOT_PW" ]; then
      echo "[x] $ENV_FILE 里找不到 MYSQL_ROOT_PASSWORD，无法备份。" >&2
      echo "    确认可以跳过备份：SKIP_DB_BACKUP=1 bash $0" >&2
      exit 1
    elif ! docker ps --format '{{.Names}}' | grep -qx 'infra-mysql'; then
      echo "[!] infra-mysql 未在运行，跳过备份（继续部署，但本次没有可回滚的快照）" >&2
    else
      mkdir -p "$BACKUP_DIR" || die "备份目录 $BACKUP_DIR 建不出来（换 BACKUP_DIR 或先清盘）"
      STAMP=$(date +%Y%m%d-%H%M%S)
      DUMP_FILE="$BACKUP_DIR/videonest-${STAMP}.sql.gz"
      echo "==> 备份 ${DB_NAME} -> ${DUMP_FILE}"
      # 密码经 MYSQL_PWD 传入，不落 argv（否则 docker inspect / ps 能看到）
      if docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PW" infra-mysql \
          mysqldump -uroot --single-transaction --quick --routines --triggers \
                    --databases "$DB_NAME" 2>/tmp/vn-dump.err | gzip > "$DUMP_FILE"; then
        BYTES=$(wc -c < "$DUMP_FILE" | tr -d ' ')
        # du -m 对任何非空文件都至少报 1，判断不出被截断的小 dump，所以按字节量
        if [ "${BYTES:-0}" -lt 1024 ]; then
          rm -f "$DUMP_FILE"
          die "备份产物只有 ${BYTES:-0} 字节，判定为失败，不用于回滚"
        fi
        echo "    备份完成：${BYTES} 字节"
        # 只保留最近 3 份：文件名带时间戳，字典序即时间序。
        # 不用 ls -t | head | xargs rm，那种写法在没有旧文件时会因 pipefail 把脚本判成失败。
        mapfile -t DUMPS < <(ls -1 "$BACKUP_DIR"/videonest-*.sql.gz 2>/dev/null || true)
        if [ "${#DUMPS[@]}" -gt 3 ]; then
          for old in "${DUMPS[@]:0:${#DUMPS[@]}-3}"; do
            echo "    清理旧备份 $(basename "$old")"
            rm -f "$old"
          done
        fi
      else
        rm -f "$DUMP_FILE"
        echo "[x] mysqldump 失败：$(cat /tmp/vn-dump.err 2>/dev/null | tail -n 3)" >&2
        echo "    多半是密码不对或库不存在。修好再部署，或明确跳过：SKIP_DB_BACKUP=1 bash $0" >&2
        exit 1
      fi
    fi
  fi
fi

# ---- 3) 只重建两个应用镜像 ----
echo "==> 构建 vn-backend / vn-frontend（仅 COPY 产物，档期：${PROFILE}）"
BUILD_START=$(date +%s)
"${COMPOSE[@]}" build vn-backend vn-frontend
BUILD_SECONDS=$(( $(date +%s) - BUILD_START ))
echo "==> 构建完成，用时 ${BUILD_SECONDS}s"


if [ "$MODE" = "build" ]; then
  echo "==> 只构建模式，未重启容器"
  exit 0
fi

# ---- 4) 重启 + 健康检查 ----
# 共享中间件不在这个 compose 里（lp-infra），先确认它活着，否则后端必然
# UnknownHostException: mysql，然后整个 up 会被 "dependency failed to start" 打断。
if ! docker ps --format '{{.Names}}' | grep -qx 'infra-mysql'; then
  echo
  echo "[!] 共享中间件没在运行：infra-mysql 不存在。"
  echo "    vn-backend 启动时会报 UnknownHostException: mysql，健康检查必然失败。"
  echo "    先执行： cd /opt/apps/learningplatform/deploy/server && ./deploy.sh infra"
  echo "    然后重跑： bash $(pwd)/deploy/server/redeploy.sh"
  echo
fi

if ! docker ps --format '{{.Names}}' | grep -qx 'vn-rabbitmq'; then
  echo "==> vn-rabbitmq 未运行，先启动它"
  "${COMPOSE[@]}" up -d rabbitmq
fi

echo "==> 重建容器（--no-deps：不因为后端健康检查把整个 up 拖垮）"
UP_START=$(date +%s)
if ! "${COMPOSE[@]}" up -d --no-deps vn-backend vn-frontend; then
  echo "[x] up -d 失败，最近日志：" >&2
  docker logs --tail 40 vn-backend >&2 2>&1 || true
  exit 1
fi
echo "==> 容器已重建，用时 $(( $(date +%s) - UP_START ))s"

echo "==> 等待 vn-backend healthy"
HEALTH="unknown"
for _ in $(seq 1 40); do
  HEALTH=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' vn-backend 2>/dev/null || echo missing)
  case "$HEALTH" in
    healthy) echo "    vn-backend healthy"; break ;;
    unhealthy)
      echo "[!] vn-backend unhealthy，最近日志：" >&2
      docker logs --tail 60 vn-backend >&2 || true
      echo >&2
      echo "    常见原因：共享中间件（infra-mysql / infra-redis / infra-minio）没起，" >&2
      echo "    执行 cd /opt/apps/learningplatform/deploy/server && ./deploy.sh infra" >&2
      echo >&2
      echo "    要退回上一版：旧 jar 还在本机 backend/target 的历史里，重新上传后跑 redeploy.sh；" >&2
      echo "    库若被本次迁移改坏，用最近一次备份还原：$(latest_backup)" >&2
      exit 1
      ;;
  esac
  sleep 3
done
[ "$HEALTH" = "healthy" ] || echo "[!] 等了 120 秒仍未 healthy（$HEALTH），请查看 docker logs vn-backend"

RUNNING=$(docker inspect -f '{{.State.Running}}' vn-frontend 2>/dev/null || echo false)
[ "$RUNNING" = "true" ] || { echo "[!] vn-frontend 未运行" >&2; docker logs --tail 40 vn-frontend >&2 || true; exit 1; }
echo "    vn-frontend running"

# 容器已切到新镜像，上一个版本的镜像这时才真正无引用；不清的话每次部署净增 79M
prune_images

echo
"${COMPOSE[@]}" ps
echo
echo "==> 完成：镜像构建 ${BUILD_SECONDS}s，本次部署总耗时约 $(( $(date +%s) - BUILD_START ))s"
echo "==> 当前档位：${PROFILE}$( [ "$PROFILE" = "bench" ] && echo '（压测档：测完请去掉 PROFILE=bench 再跑一次还原演示档）' )"
if [ "$PROFILE" = "bench" ]; then
  echo "    压测档：测完请不带 PROFILE 再跑一次 redeploy.sh，把演示档还原回来。"
fi
echo "==> 可回滚的最近备份：$(latest_backup)"
