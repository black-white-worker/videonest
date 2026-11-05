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
#   bash deploy/server/redeploy.sh          # 构建 + 重启 + 等健康
#   bash deploy/server/redeploy.sh build    # 只构建，不重启
# =====================================================================
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

ENV_FILE="${ENV_FILE:-.env.prod}"
COMPOSE=(docker compose -f docker-compose.server.yml --env-file "$ENV_FILE")
BASE_IMAGE="videonest-backend-base:latest"
MODE="${1:-up}"

die() { echo "[x] $*" >&2; exit 1; }

[ -f "$ENV_FILE" ] || die "$ENV_FILE 不存在（先 cp .env.prod.example .env.prod 再填）"
[ -f backend/target/app.jar ] || die "backend/target/app.jar 不存在，请在本机先跑 scripts/deploy.ps1"
[ -d frontend/dist ] || die "frontend/dist 不存在，请在本机先跑 scripts/deploy.ps1"

# ---- 1) 运行基础镜像（含 ffmpeg），只建一次 ----
if docker image inspect "$BASE_IMAGE" >/dev/null 2>&1; then
  echo "[base] $BASE_IMAGE 已存在，跳过（要重建先 docker rmi $BASE_IMAGE）"
else
  echo "[base] 首次构建 $BASE_IMAGE（JRE + ffmpeg，约 1~3 分钟，只做一次）"
  docker build -f backend/Dockerfile.base -t "$BASE_IMAGE" backend
fi

# ---- 2) 只重建两个应用镜像 ----
echo "==> 构建 vn-backend / vn-frontend（仅 COPY 产物）"
BUILD_START=$(date +%s)
"${COMPOSE[@]}" build vn-backend vn-frontend
BUILD_SECONDS=$(( $(date +%s) - BUILD_START ))
echo "==> 构建完成，用时 ${BUILD_SECONDS}s"

if [ "$MODE" = "build" ]; then
  echo "==> 只构建模式，未重启容器"
  exit 0
fi

# ---- 3) 重启 + 健康检查 ----
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
      exit 1
      ;;
  esac
  sleep 3
done
[ "$HEALTH" = "healthy" ] || echo "[!] 等了 120 秒仍未 healthy（$HEALTH），请查看 docker logs vn-backend"

RUNNING=$(docker inspect -f '{{.State.Running}}' vn-frontend 2>/dev/null || echo false)
[ "$RUNNING" = "true" ] || { echo "[!] vn-frontend 未运行" >&2; docker logs --tail 40 vn-frontend >&2 || true; exit 1; }
echo "    vn-frontend running"

echo
"${COMPOSE[@]}" ps
echo
echo "==> 完成：镜像构建 ${BUILD_SECONDS}s，本次部署总耗时约 $(( $(date +%s) - BUILD_START ))s"
