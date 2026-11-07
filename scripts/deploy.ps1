# Deploy videonest to the shared-infra server (artifact mode).
#
# Before: the whole source tree was uploaded and Docker compiled it on the
#         server (2 vCPU / 1.8 GB). One build took ~21 minutes.
# Now: this machine builds the jar + dist, uploads ~80 MB, and the server only
#      COPYs them into thin runtime images (build: 10-30 seconds).
#
# What it does:
#   1) local mvn package (tests skipped by default, same as the old server build)
#   2) local npm ci (only when node_modules is missing) + npm run build
#   3) pack jar/dist/Dockerfiles/compose files into one tar.gz and upload it
#   4) run deploy/server/redeploy.sh on the server:
#      ensure base image -> build two images -> up -d -> wait for health
#
# Usage:
#   .\deploy.cmd                      # standard deploy
#   .\deploy.cmd -RunTests            # also run backend tests (needs local Docker, slower)
#   .\deploy.cmd -SkipBuild           # reuse backend\target\*.jar and frontend\dist
#   .\deploy.cmd -SkipUpload          # do not upload, just rebuild from the server copy
#   .\deploy.cmd -PublicSiteUrl "https://example.com"   # baked into the frontend build
param(
    [string]$Server = "101.201.208.223",
    [string]$RemoteUser = "root",
    [string]$RemoteDir = "/opt/apps/videonest",
    [string]$IdentityFile,
    [string]$PublicSiteUrl,
    [switch]$RunTests,
    [switch]$SkipBuild,
    [switch]$SkipUpload
)

$ErrorActionPreference = "Stop"

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Target = "$RemoteUser@$Server"
$Stage = Join-Path $env:TEMP "videonest-deploy-stage"
$Archive = Join-Path $env:TEMP "videonest-deploy.tar.gz"

$SshArgs = @('-o', 'ConnectTimeout=15')
if ($IdentityFile) {
    $SshArgs += @('-i', (Resolve-Path -LiteralPath $IdentityFile).Path)
}

function Invoke-OnServer {
    param([string]$Command)
    & ssh @SshArgs $Target $Command
    if ($LASTEXITCODE -ne 0) { throw "Remote command failed (exit $LASTEXITCODE): $Command" }
}

Write-Host "Target : $Target"
Write-Host "Remote : $RemoteDir"
Write-Host ""

# ---------------------------------------------------------------------
# 0) connectivity / docker checks
# ---------------------------------------------------------------------
& ssh @SshArgs $Target "echo CONNECT_OK" | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Cannot connect to $Target (check SSH key and security group)." }

$DockerOk = ((& ssh @SshArgs $Target "command -v docker >/dev/null 2>&1 && echo YES || echo NO") | Out-String).Trim()
if ($DockerOk -ne "YES") {
    throw "Docker is not installed on the server. Run first: bash /opt/apps/learningplatform/deploy/server/bootstrap.sh"
}

$Total = [Diagnostics.Stopwatch]::StartNew()

# ---------------------------------------------------------------------
# 1) local builds
# ---------------------------------------------------------------------
if (-not $SkipBuild) {
    Write-Host "[1/5] build backend jar locally"

    $Maven = $null
    foreach ($candidate in @('mvn.cmd', 'mvn')) {
        if (Get-Command $candidate -ErrorAction SilentlyContinue) { $Maven = $candidate; break }
    }
    if (-not $Maven) {
        $wrapper = Join-Path $ProjectRoot "backend\mvnw.cmd"
        if (Test-Path -LiteralPath $wrapper) { $Maven = $wrapper }
    }
    if (-not $Maven) { throw "Maven not found. Install Maven or keep backend\mvnw.cmd." }

    $sw = [Diagnostics.Stopwatch]::StartNew()
    $MavenArgs = @("-B", "-f", (Join-Path $ProjectRoot "backend\pom.xml"), "clean", "package")
    if (-not $RunTests) { $MavenArgs += "-DskipTests" }
    & $Maven @MavenArgs
    if ($LASTEXITCODE -ne 0) {
        if ($RunTests) {
            Write-Host "Backend tests/build failed. See backend\target\surefire-reports." -ForegroundColor Red
        }
        throw "Backend package failed (exit code $LASTEXITCODE)"
    }
    Write-Host ("      jar  done in {0:N0}s" -f $sw.Elapsed.TotalSeconds)

    Write-Host "[2/5] build frontend dist locally"
    $sw.Restart()

    $Npm = $null
    foreach ($candidate in @('npm.cmd', 'npm')) {
        if (Get-Command $candidate -ErrorAction SilentlyContinue) { $Npm = $candidate; break }
    }
    if (-not $Npm) { throw "npm not found." }

    $FrontendDir = Join-Path $ProjectRoot "frontend"
    $PreviousSiteUrl = [Environment]::GetEnvironmentVariable("VITE_PUBLIC_SITE_URL", "Process")
    if ($PublicSiteUrl) { $env:VITE_PUBLIC_SITE_URL = $PublicSiteUrl }
    try {
        if (-not (Test-Path (Join-Path $FrontendDir "node_modules\.bin\vite.cmd"))) {
            & $Npm --prefix $FrontendDir ci --no-audit --no-fund --registry https://registry.npmmirror.com
            if ($LASTEXITCODE -ne 0) { throw "npm ci failed (exit $LASTEXITCODE)" }
        }
        & $Npm --prefix $FrontendDir run build
        if ($LASTEXITCODE -ne 0) { throw "Frontend build failed (exit $LASTEXITCODE)" }
    }
    finally {
        if ($null -eq $PreviousSiteUrl) {
            Remove-Item Env:\VITE_PUBLIC_SITE_URL -ErrorAction SilentlyContinue
        }
        else {
            $env:VITE_PUBLIC_SITE_URL = $PreviousSiteUrl
        }
    }
    Write-Host ("      dist done in {0:N0}s" -f $sw.Elapsed.TotalSeconds)
}
else {
    Write-Host "[1/5] -SkipBuild: reuse existing jar"
    Write-Host "[2/5] -SkipBuild: reuse existing dist"
}

# ---------------------------------------------------------------------
# 3) validate artifacts + stage them
# ---------------------------------------------------------------------
$Jar = Get-ChildItem (Join-Path $ProjectRoot "backend\target") -Filter "*.jar" -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch '(-sources|-javadoc|\.original)$' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $Jar) { throw "backend\target\*.jar not found. Run again without -SkipBuild." }

$Dist = Join-Path $ProjectRoot "frontend\dist"
if (-not (Test-Path -LiteralPath $Dist)) { throw "frontend\dist not found. Run again without -SkipBuild." }

Write-Host ("[3/5] stage artifacts (jar {0:N1} MB)" -f ($Jar.Length / 1MB))

if (Test-Path $Stage) { Remove-Item $Stage -Recurse -Force }
foreach ($dir in @("backend\target", "frontend", "deploy\server")) {
    New-Item -ItemType Directory -Force -Path (Join-Path $Stage $dir) | Out-Null
}

Copy-Item $Jar.FullName (Join-Path $Stage "backend\target\app.jar") -Force
Copy-Item $Dist (Join-Path $Stage "frontend\dist") -Recurse -Force

$UploadFiles = @(
    "backend\Dockerfile.jar",
    "backend\Dockerfile.base",
    "backend\Dockerfile.jar.dockerignore",
    "backend\Dockerfile.base.dockerignore",
    "frontend\Dockerfile.dist",
    "frontend\Dockerfile.dist.dockerignore",
    "frontend\nginx.conf",
    "deploy\server\nginx.conf",
    "deploy\server\redeploy.sh",
    "scripts\prefetch-images.sh",
    "deploy\rabbitmq\Dockerfile",
    "docker-compose.server.yml",
    "docker-compose.source.yml"
)
foreach ($relative in $UploadFiles) {
    $source = Join-Path $ProjectRoot $relative
    if (-not (Test-Path -LiteralPath $source)) { throw "Missing file: $relative" }
    $destination = Join-Path $Stage $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
    Copy-Item $source $destination -Force
}

# Optional files: absent they do not break the normal (idle-profile) deploy, they
# only make PROFILE=bench unusable on the server. Warn instead of throwing so the
# benchmark overlay is not coupled to every release.
# NOTE: keep this block ASCII. These two scripts are stored as BOM-less UTF-8, so
# Windows PowerShell 5.1 decodes them as ANSI/GBK and a multibyte character whose
# trailing byte lands on the newline swallows that newline (comment eats next line).
$OptionalUploadFiles = @(
    "docker-compose.bench.yml"
)
foreach ($relative in $OptionalUploadFiles) {
    $source = Join-Path $ProjectRoot $relative
    if (Test-Path -LiteralPath $source) {
        $destination = Join-Path $Stage $relative
        New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
        Copy-Item $source $destination -Force
    }
    else {
        Write-Warning "Optional file missing: $relative (PROFILE=bench will not work on the server)"
    }
}

# ---------------------------------------------------------------------
# 4) pack + upload
# ---------------------------------------------------------------------
if ($SkipUpload) {
    Write-Host "[4/5] -SkipUpload: nothing uploaded"
}
else {
    Write-Host "[4/5] upload artifacts"
    if (Test-Path $Archive) { Remove-Item $Archive -Force }
    Push-Location $Stage
    try {
        & tar -czf $Archive .
        if ($LASTEXITCODE -ne 0) { throw "Packaging failed." }
    }
    finally {
        Pop-Location
    }

    $ArchiveMb = [math]::Round((Get-Item $Archive).Length / 1MB, 1)
    Write-Host "      archive $ArchiveMb MB"

    $sw = [Diagnostics.Stopwatch]::StartNew()
    & scp @SshArgs $Archive "${Target}:/tmp/videonest-deploy.tar.gz"
    if ($LASTEXITCODE -ne 0) { throw "Upload failed." }

    $remoteCommand = "set -e; mkdir -p '$RemoteDir'; tar -xzf /tmp/videonest-deploy.tar.gz -C '$RemoteDir'; rm -f /tmp/videonest-deploy.tar.gz; sed -i 's/\r$//' '$RemoteDir/deploy/server/redeploy.sh' '$RemoteDir/scripts/prefetch-images.sh'; chmod +x '$RemoteDir/deploy/server/redeploy.sh' '$RemoteDir/scripts/prefetch-images.sh'"
    Invoke-OnServer $remoteCommand
    Write-Host ("      uploaded and extracted in {0:N0}s" -f $sw.Elapsed.TotalSeconds)
}

# ---------------------------------------------------------------------
# 5) rebuild on the server
# ---------------------------------------------------------------------
$EnvOk = ((& ssh @SshArgs $Target "test -f '$RemoteDir/.env.prod' && echo YES || echo NO") | Out-String).Trim()
if ($EnvOk -ne "YES") {
    throw "$RemoteDir/.env.prod is missing on the server. Create it before deploying."
}

Write-Host "[5/5] rebuild on the server (COPY only)"
& ssh @SshArgs $Target "bash '$RemoteDir/deploy/server/redeploy.sh'"
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "Server rebuild failed. Most common cause: the shared middleware (lp-infra) is not running," -ForegroundColor Red
    Write-Host "so vn-backend cannot resolve 'mysql' and the health check fails." -ForegroundColor Red
    Write-Host "Start it first, then re-run with -SkipBuild -SkipUpload:" -ForegroundColor Yellow
    Write-Host "  ssh $Target 'cd /opt/apps/learningplatform/deploy/server && ./deploy.sh infra'" -ForegroundColor Yellow
    throw "Remote deploy failed (exit $LASTEXITCODE)"
}

Write-Host ""
Write-Host ("Done in {0:N1} min: http://{1}:8082/" -f $Total.Elapsed.TotalMinutes, $Server)
