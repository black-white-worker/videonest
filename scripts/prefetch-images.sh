#!/usr/bin/env bash
# =====================================================================
# 预热 videonest 真正用到的镜像（在服务器上执行）
#
#   bash scripts/prefetch-images.sh            # 只拉部署必需的基础镜像
#   bash scripts/prefetch-images.sh --source   # 额外拉“源码模式”用的 maven/node
#
# 说明：
#   * 服务器的 /etc/docker/daemon.json 已配置镜像加速（daocloud / 1ms / 轩辕），
#     所以这里直接 docker pull 普通名字即可，daemon 会按顺序自动换站，不需要手动
#     加 docker.m.daocloud.io/ 前缀再 docker tag。
#   * 每个镜像 240 秒超时，失败只记录、不中断，最后统一汇报。
#
# 常见失败：
#   minio/mc、minio/minio 在 Docker Hub 上已归档，镜像站经常报 "denied / repository
#   does not exist"。可以改用 quay.io：
#     docker pull quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z
#     docker pull quay.io/minio/mc:RELEASE.2025-08-13T08-35-41Z
# =====================================================================
set -uo pipefail

WITH_SOURCE=0
[ "${1:-}" = "--source" ] && WITH_SOURCE=1

# 产物模式部署必需
IMAGES=(
  "eclipse-temurin:21-jre-alpine"                    # 后端运行基础镜像（含 ffmpeg）
  "nginx:1.29-alpine"                                # 前端静态站点
  "rabbitmq:4.2.0-management"                        # vn-rabbitmq 的构建基底
  "mysql:8.4"                                        # 本地/单机 compose 用
  "redis:7.4-alpine"
  "minio/minio:RELEASE.2025-09-07T16-13-09Z"
  "minio/mc:RELEASE.2025-08-13T08-35-41Z"
)

# 只有“源码模式”（docker-compose.source.yml）才需要
SOURCE_IMAGES=(
  "maven:3.9.11-eclipse-temurin-21-alpine"
  "node:24-alpine"
)

if [ "$WITH_SOURCE" = "1" ]; then
  IMAGES+=("${SOURCE_IMAGES[@]}")
fi

FAILED=()
PULL_TIMEOUT=240

pull() {
  local image="$1"
  if docker image inspect "$image" >/dev/null 2>&1; then
    echo "[skip] $image"
    return 0
  fi
  echo "[pull] $image"
  if timeout "$PULL_TIMEOUT" docker pull "$image" >/tmp/prefetch-one.log 2>&1; then
    echo "       ok"
    return 0
  fi
  echo "       FAILED: $(tail -1 /tmp/prefetch-one.log 2>/dev/null)" >&2
  FAILED+=("$image")
  return 0
}

for image in "${IMAGES[@]}"; do
  pull "$image"
done

echo
echo "================ 本地镜像 ================"
docker images --format '{{.Repository}}:{{.Tag}}' | sort | grep -E 'temurin|nginx|rabbitmq|mysql|redis|minio|maven|node' || true

if [ ${#FAILED[@]} -gt 0 ]; then
  echo
  echo "以下镜像没拉到（不影响已缓存的构建，但首次构建会变慢或失败）："
  printf '  - %s\n' "${FAILED[@]}"
  exit 1
fi

echo
echo "全部就绪"
