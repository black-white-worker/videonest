param(
    [string]$Server = "82.157.205.6",
    [string]$RemoteUser = "ubuntu",
    [int]$SshPort = 22,
    [string]$RemoteDir = "/opt/videonest",
    [string]$IdentityFile,
    [string]$EnvFile,
    [string]$PublicSiteUrl,
    [string]$MinioPublicEndpoint,
    [switch]$SkipBuild,
    [switch]$SkipTests,
    [switch]$SkipBackup
)

$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Archive = Join-Path $ProjectRoot "videonest-deploy.tar.gz"
$Target = "${RemoteUser}@${Server}"
$RemoteToken = [Guid]::NewGuid().ToString("N")
$RemoteArchive = "/tmp/videonest-deploy-$RemoteToken.tar.gz"
$RemoteEnv = "/tmp/videonest-$RemoteToken.env"
$GeneratedEnv = $null

function Get-EnvValues([string]$Path) {
    $Values = @{}
    foreach ($Line in Get-Content -LiteralPath $Path) {
        if ($Line -match '^\s*#' -or [string]::IsNullOrWhiteSpace($Line)) { continue }
        if ($Line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            $Value = $matches[2].Trim()
            if (($Value.StartsWith('"') -and $Value.EndsWith('"')) -or
                ($Value.StartsWith("'") -and $Value.EndsWith("'"))) {
                $Value = $Value.Substring(1, $Value.Length - 2)
            }
            $Values[$matches[1]] = $Value
        }
    }
    return $Values
}

function New-ServerEnv([string]$Source, [hashtable]$Overrides) {
    $Destination = [System.IO.Path]::GetTempFileName()
    $Written = @{}
    $Output = foreach ($Line in Get-Content -LiteralPath $Source) {
        if ($Line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=') {
            $Key = $matches[1]
            if ($Overrides.ContainsKey($Key)) {
                $Written[$Key] = $true
                "$Key=$($Overrides[$Key])"
                continue
            }
        }
        $Line
    }
    foreach ($Key in $Overrides.Keys) {
        if (-not $Written.ContainsKey($Key)) {
            $Output += "$Key=$($Overrides[$Key])"
        }
    }
    [System.IO.File]::WriteAllLines($Destination, $Output, [System.Text.UTF8Encoding]::new($false))
    return $Destination
}

if ($Server -notmatch '^[A-Za-z0-9.-]+$') { throw "Server contains unsupported characters: $Server" }
if ($RemoteUser -notmatch '^[A-Za-z0-9._-]+$') { throw "RemoteUser contains unsupported characters: $RemoteUser" }
if ($SshPort -lt 1 -or $SshPort -gt 65535) { throw "SshPort must be between 1 and 65535." }
if ($RemoteDir -notmatch '^/[A-Za-z0-9._/-]+$' -or $RemoteDir -eq '/') {
    throw "RemoteDir must be a safe absolute directory and cannot be /."
}

if (-not $EnvFile) { $EnvFile = Join-Path $ProjectRoot ".env" }
$EnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
if (-not $PublicSiteUrl) { $PublicSiteUrl = "http://$Server" }
if (-not $MinioPublicEndpoint) { $MinioPublicEndpoint = "http://$Server`:9000" }

foreach ($Url in @($PublicSiteUrl, $MinioPublicEndpoint)) {
    $Parsed = $null
    if (-not [Uri]::TryCreate($Url, [UriKind]::Absolute, [ref]$Parsed) -or
        $Parsed.Scheme -notin @('http', 'https')) {
        throw "Only an absolute HTTP/HTTPS URL is supported: $Url"
    }
    if ($Parsed.Host -in @('localhost', '127.0.0.1') -and $Server -notin @('localhost', '127.0.0.1')) {
        throw "A remote deployment cannot expose a localhost URL: $Url"
    }
}
if ($PublicSiteUrl.StartsWith('https://') -and $MinioPublicEndpoint.StartsWith('http://')) {
    throw "HTTPS pages cannot load media over HTTP. Configure MinioPublicEndpoint with HTTPS too."
}

$EnvValues = Get-EnvValues $EnvFile
$RequiredKeys = @(
    'DB_NAME', 'DB_USERNAME', 'DB_PASSWORD', 'MYSQL_ROOT_PASSWORD',
    'REDIS_PASSWORD', 'RABBITMQ_USERNAME', 'RABBITMQ_PASSWORD',
    'MINIO_ACCESS_KEY', 'MINIO_SECRET_KEY', 'MINIO_BUCKET', 'JWT_SECRET',
    'VIDEO_REVIEW_TIMEOUT_MILLISECONDS', 'RESOURCE_RETENTION_DAYS'
)
foreach ($Key in $RequiredKeys) {
    $Value = $EnvValues[$Key]
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -match '(?i)CHANGE_ME|YOUR_|EXAMPLE') {
        throw "$Key is missing or still uses a placeholder in $EnvFile"
    }
}
if ($EnvValues['JWT_SECRET'].Length -lt 32) { throw "JWT_SECRET must contain at least 32 characters." }
if ($EnvValues['MINIO_SECRET_KEY'].Length -lt 8) { throw "MINIO_SECRET_KEY must contain at least 8 characters." }
if ($EnvValues['DB_USERNAME'] -ne 'root') {
    Write-Warning "docker-compose.yml creates only the MySQL root account. Ensure DB_USERNAME already exists on the server."
}
if ($EnvValues['MYSQL_ROOT_PASSWORD'].Length -lt 12) {
    Write-Warning "MYSQL_ROOT_PASSWORD is short. Use a stronger password before the first production deployment."
}

$SshArgs = @('-p', "$SshPort", '-o', 'ConnectTimeout=15')
$ScpArgs = @('-P', "$SshPort", '-o', 'ConnectTimeout=15')
if ($IdentityFile) {
    $ResolvedIdentity = (Resolve-Path -LiteralPath $IdentityFile).Path
    $SshArgs += @('-i', $ResolvedIdentity)
    $ScpArgs += @('-i', $ResolvedIdentity)
}
$Sudo = if ($RemoteUser -eq 'root') { '' } else { 'sudo' }

try {
    $Overrides = @{
        MINIO_PUBLIC_ENDPOINT = $MinioPublicEndpoint.TrimEnd('/')
        MINIO_API_CORS_ALLOW_ORIGIN = $PublicSiteUrl.TrimEnd('/')
        VITE_PUBLIC_SITE_URL = $PublicSiteUrl.TrimEnd('/')
        FLYWAY_BASELINE_VERSION = '3'
    }
    $GeneratedEnv = New-ServerEnv $EnvFile $Overrides

    if (-not $SkipBuild) {
        & (Join-Path $PSScriptRoot "package-deploy.ps1") `
            -OutputPath $Archive `
            -PublicSiteUrl $PublicSiteUrl `
            -SkipTests:$SkipTests
        if ($LASTEXITCODE -ne 0) { throw "Local package failed" }
    } elseif (-not (Test-Path -LiteralPath $Archive)) {
        throw "Missing $Archive; run without -SkipBuild first."
    } else {
        Write-Warning "SkipBuild reuses the existing archive. Confirm it was built for $PublicSiteUrl"
    }

    Write-Host "Preparing $Target`:$RemoteDir ..."
    $PrepareCommand = if ($Sudo) {
        "$Sudo mkdir -p '$RemoteDir' && $Sudo chown '$RemoteUser' '$RemoteDir'"
    } else {
        "mkdir -p '$RemoteDir'"
    }
    & ssh @SshArgs $Target $PrepareCommand
    if ($LASTEXITCODE -ne 0) { throw "Unable to prepare remote directory" }

    & scp @ScpArgs $Archive "${Target}:$RemoteArchive"
    if ($LASTEXITCODE -ne 0) { throw "Archive upload failed" }
    & scp @ScpArgs $GeneratedEnv "${Target}:$RemoteEnv"
    if ($LASTEXITCODE -ne 0) { throw "Environment upload failed" }

    $RemoteScript = @'
set -Eeuo pipefail
REMOTE_DIR='__REMOTE_DIR__'
REMOTE_ARCHIVE='__REMOTE_ARCHIVE__'
REMOTE_ENV='__REMOTE_ENV__'
SKIP_BACKUP='__SKIP_BACKUP__'
DOCKER=(__DOCKER_PREFIX__ docker)
COMPOSE=("${DOCKER[@]}" compose -f docker-compose.yml -f docker-compose.jar.yml)

cleanup() {
  rm -f "$REMOTE_ARCHIVE" "$REMOTE_ENV"
}
trap cleanup EXIT

command -v docker >/dev/null 2>&1 || { echo 'Docker is not installed on the server.' >&2; exit 1; }
"${DOCKER[@]}" compose version >/dev/null
tar -xzf "$REMOTE_ARCHIVE" -C "$REMOTE_DIR"
install -m 600 "$REMOTE_ENV" "$REMOTE_DIR/.env"
cd "$REMOTE_DIR"
"${COMPOSE[@]}" config --quiet

if [ "$SKIP_BACKUP" != 'true' ]; then
  MYSQL_CONTAINER="$("${COMPOSE[@]}" ps -q mysql 2>/dev/null || true)"
  if [ -z "$MYSQL_CONTAINER" ]; then
    MYSQL_CONTAINER="$("${DOCKER[@]}" ps -q --filter name=^/videonest-mysql$ | head -n 1)"
  fi
  if [ -n "$MYSQL_CONTAINER" ] && [ "$("${DOCKER[@]}" inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER")" = 'true' ]; then
    mkdir -p backups
    BACKUP_FILE="backups/pre-deploy-$(date +%Y%m%d-%H%M%S).sql"
    echo "Backing up MySQL to $REMOTE_DIR/$BACKUP_FILE ..."
    "${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'exec mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers "$MYSQL_DATABASE"' > "$BACKUP_FILE"
    test -s "$BACKUP_FILE"
  else
    echo 'No running MySQL container found; this is treated as the first deployment.'
  fi
fi

# MySQL 的 DDL 可能在旧版 V5 失败后留下 Flyway 失败记录。先备份，再补齐 V5 的缺失字段；
# 三个字段全部存在后才把该条历史修复为成功，保留既有字段和业务数据。
MYSQL_CONTAINER="$("${COMPOSE[@]}" ps -q mysql 2>/dev/null || true)"
if [ -z "$MYSQL_CONTAINER" ]; then
  MYSQL_CONTAINER="$("${DOCKER[@]}" ps -q --filter name=^/videonest-mysql$ | head -n 1)"
fi
if [ -n "$MYSQL_CONTAINER" ] && [ "$("${DOCKER[@]}" inspect -f '{{.State.Running}}' "$MYSQL_CONTAINER")" = 'true' ]; then
  FAILED_V5="$("${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -Nse "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '\''5'\'' AND success = 0" 2>/dev/null' || true)"
  if [ "$FAILED_V5" = '1' ]; then
    echo 'Repairing the failed Flyway V5 schema; business data is not removed ...'
    for COLUMN_NAME in original_cover_url cover_list_url cover_detail_url; do
      COLUMN_EXISTS="$("${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c "mysql -u root -p\"\$MYSQL_ROOT_PASSWORD\" \"\$MYSQL_DATABASE\" -Nse \"SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'video' AND COLUMN_NAME = '$COLUMN_NAME'\"")"
      if [ "$COLUMN_EXISTS" = '0' ]; then
        case "$COLUMN_NAME" in
          original_cover_url)
            COLUMN_DDL="ALTER TABLE video ADD COLUMN original_cover_url VARCHAR(500) NULL COMMENT '用户上传的原始封面对象名，不直接返回给浏览器' AFTER cover_url"
            ;;
          cover_list_url)
            COLUMN_DDL="ALTER TABLE video ADD COLUMN cover_list_url VARCHAR(500) NULL COMMENT '400px 列表封面对象名' AFTER original_cover_url"
            ;;
          cover_detail_url)
            COLUMN_DDL="ALTER TABLE video ADD COLUMN cover_detail_url VARCHAR(500) NULL COMMENT '1080px 详情封面对象名' AFTER cover_list_url"
            ;;
        esac
        "${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c "exec mysql -u root -p\"\$MYSQL_ROOT_PASSWORD\" \"\$MYSQL_DATABASE\" -e \"$COLUMN_DDL\""
      fi
    done
    V5_COLUMN_COUNT="$("${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -Nse "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '\''video'\'' AND COLUMN_NAME IN ('\''original_cover_url'\'', '\''cover_list_url'\'', '\''cover_detail_url'\'')"')"
    if [ "$V5_COLUMN_COUNT" != '3' ]; then
      echo 'Flyway V5 repair stopped because the three media columns are incomplete.' >&2
      exit 1
    fi
    "${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e "UPDATE flyway_schema_history SET success = 1 WHERE version = '\''5'\'' AND success = 0"'
    echo 'Flyway V5 repair completed.'
  fi

  FAILED_V6="$("${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -Nse "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '\''6'\'' AND success = 0" 2>/dev/null' || true)"
  if [ "$FAILED_V6" = '1' ]; then
    echo 'Repairing the failed Flyway V6 schema; comment data is preserved ...'
    "${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e "DROP PROCEDURE IF EXISTS migrate_comment_root_id"'

    ROOT_ID_EXISTS="$("${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -Nse "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '\''video_comment'\'' AND COLUMN_NAME = '\''root_id'\''"')"
    if [ "$ROOT_ID_EXISTS" = '0' ]; then
      "${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e "ALTER TABLE video_comment ADD COLUMN root_id BIGINT NOT NULL DEFAULT 0 COMMENT '\''所属一级评论ID；一级评论为0'\'' AFTER parent_id"'
    fi

    CASCADE_ROOT_EXISTS="$("${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -Nse "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '\''video_comment'\'' AND COLUMN_NAME = '\''cascade_deleted_root_id'\''"')"
    if [ "$CASCADE_ROOT_EXISTS" = '0' ]; then
      "${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e "ALTER TABLE video_comment ADD COLUMN cascade_deleted_root_id BIGINT NULL COMMENT '\''因一级评论删除而被级联删除时记录根评论ID'\'' AFTER deleted_at"'
    fi

    ROOT_INDEX_EXISTS="$("${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -Nse "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '\''video_comment'\'' AND INDEX_NAME = '\''idx_comment_video_root_status_time'\''"')"
    if [ "$ROOT_INDEX_EXISTS" = '0' ]; then
      "${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e "ALTER TABLE video_comment ADD KEY idx_comment_video_root_status_time (video_id, root_id, status, created_at, id)"'
    fi

    "${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e "UPDATE video_comment SET root_id = parent_id WHERE parent_id <> 0 AND root_id = 0"'
    V6_COLUMN_COUNT="$("${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -Nse "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '\''video_comment'\'' AND COLUMN_NAME IN ('\''root_id'\'', '\''cascade_deleted_root_id'\'')"')"
    ROOT_INDEX_EXISTS="$("${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -Nse "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '\''video_comment'\'' AND INDEX_NAME = '\''idx_comment_video_root_status_time'\''"')"
    if [ "$V6_COLUMN_COUNT" != '2' ] || [ "$ROOT_INDEX_EXISTS" = '0' ]; then
      echo 'Flyway V6 repair stopped because the comment columns or index are incomplete.' >&2
      exit 1
    fi
    "${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e "UPDATE flyway_schema_history SET success = 1 WHERE version = '\''6'\'' AND success = 0"'
    echo 'Flyway V6 repair completed.'
  fi

  FAILED_V7="$("${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -Nse "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '\''7'\'' AND success = 0" 2>/dev/null' || true)"
  if [ "$FAILED_V7" = '1' ]; then
    echo 'Repairing the failed Flyway V7 outbox schema; existing events are preserved ...'
    "${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e "CREATE TABLE IF NOT EXISTS outbox_event (id BIGINT NOT NULL AUTO_INCREMENT, event_id VARCHAR(64) NOT NULL, event_type VARCHAR(64) NOT NULL, exchange_name VARCHAR(128) NOT NULL, routing_key VARCHAR(128) NOT NULL, payload TEXT NOT NULL, status VARCHAR(20) NOT NULL DEFAULT '\''PENDING'\'', retry_count INT NOT NULL DEFAULT 0, next_retry_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, last_error VARCHAR(500) NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, sent_at DATETIME NULL, PRIMARY KEY (id), UNIQUE KEY uk_outbox_event_id (event_id), KEY idx_outbox_pending (status, next_retry_at, id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='\''事务消息发件箱'\''"'
    V7_COLUMN_COUNT="$("${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -Nse "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '\''outbox_event'\'' AND COLUMN_NAME IN ('\''id'\'', '\''event_id'\'', '\''event_type'\'', '\''exchange_name'\'', '\''routing_key'\'', '\''payload'\'', '\''status'\'', '\''retry_count'\'', '\''next_retry_at'\'', '\''last_error'\'', '\''created_at'\'', '\''updated_at'\'', '\''sent_at'\'')"')"
    V7_INDEX_COUNT="$("${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -Nse "SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '\''outbox_event'\'' AND INDEX_NAME IN ('\''PRIMARY'\'', '\''uk_outbox_event_id'\'', '\''idx_outbox_pending'\'')"')"
    if [ "$V7_COLUMN_COUNT" != '13' ] || [ "$V7_INDEX_COUNT" != '3' ]; then
      echo 'Flyway V7 repair stopped because the outbox table structure is incomplete.' >&2
      exit 1
    fi
    "${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'exec mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -e "UPDATE flyway_schema_history SET success = 1 WHERE version = '\''7'\'' AND success = 0"'
    echo 'Flyway V7 repair completed.'
  fi

  FAILED_CURRENT_MIGRATIONS="$("${DOCKER[@]}" exec "$MYSQL_CONTAINER" sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -Nse "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('\''5'\'', '\''6'\'', '\''7'\'') AND success = 0" 2>/dev/null' || true)"
  if [ "${FAILED_CURRENT_MIGRATIONS:-0}" != '0' ]; then
    echo 'Flyway repair stopped because V5-V7 still contain failed history entries.' >&2
    exit 1
  fi
  echo 'Flyway V5-V7 preflight passed.'
fi

BACKEND_RUNTIME_IMAGE="$(sed -n 's/^BACKEND_RUNTIME_IMAGE=//p' .env | tail -n 1)"
BACKEND_RUNTIME_IMAGE="${BACKEND_RUNTIME_IMAGE:-videonest-backend:latest}"
if ! "${DOCKER[@]}" image inspect "$BACKEND_RUNTIME_IMAGE" >/dev/null 2>&1; then
  echo "Missing server runtime image: $BACKEND_RUNTIME_IMAGE" >&2
  echo 'This deployment intentionally does not download FFmpeg. Keep the previously built FFmpeg backend image on the server.' >&2
  exit 1
fi

echo 'Building backend and frontend from local packaged artifacts ...'
"${COMPOSE[@]}" build backend frontend
if ! "${COMPOSE[@]}" up -d --no-build --remove-orphans; then
  echo 'Container startup failed. Backend diagnostics follow:' >&2
  "${COMPOSE[@]}" ps -a >&2 || true
  "${COMPOSE[@]}" logs --tail=240 backend mysql redis rabbitmq minio >&2 || true
  BACKEND_CONTAINER="$("${COMPOSE[@]}" ps -q backend 2>/dev/null || true)"
  if [ -n "$BACKEND_CONTAINER" ]; then
    "${DOCKER[@]}" inspect --format '{{json .State.Health}}' "$BACKEND_CONTAINER" >&2 || true
  fi
  exit 1
fi

HTTP_PORT="$(sed -n 's/^HTTP_PORT=//p' .env | tail -n 1)"
HTTP_PORT="${HTTP_PORT:-80}"
ready='false'
for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${HTTP_PORT}/api/videos?page=1&size=1" >/dev/null 2>&1 &&
     curl -fsS 'http://127.0.0.1:9000/minio/health/live' >/dev/null 2>&1; then
    ready='true'
    break
  fi
  sleep 3
done

if [ "$ready" != 'true' ]; then
  echo 'Deployment health check failed.' >&2
  "${COMPOSE[@]}" ps >&2
  "${COMPOSE[@]}" logs --tail=160 backend frontend minio >&2
  exit 1
fi

"${COMPOSE[@]}" ps
echo 'Deployment health check passed.'
'@
    $RemoteScript = $RemoteScript.Replace('__REMOTE_DIR__', $RemoteDir)
    $RemoteScript = $RemoteScript.Replace('__REMOTE_ARCHIVE__', $RemoteArchive)
    $RemoteScript = $RemoteScript.Replace('__REMOTE_ENV__', $RemoteEnv)
    $RemoteScript = $RemoteScript.Replace('__SKIP_BACKUP__', $SkipBackup.IsPresent.ToString().ToLowerInvariant())
    $RemoteScript = $RemoteScript.Replace('__DOCKER_PREFIX__', $Sudo)

    $RemoteScript | & ssh @SshArgs $Target 'bash -s'
    if ($LASTEXITCODE -ne 0) { throw "Remote deployment failed" }

    Write-Host "Deployment complete: $PublicSiteUrl"
    Write-Host "Media endpoint: $MinioPublicEndpoint"
} finally {
    if ($GeneratedEnv -and (Test-Path -LiteralPath $GeneratedEnv)) {
        Remove-Item -LiteralPath $GeneratedEnv -Force
    }
}
