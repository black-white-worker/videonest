# Local development / local deployment helper for videonest.
#
# Two very different loops:
#   dev / infra  -> run the app from source with hot reload (what you want while coding)
#   app          -> build the same artifact images as production and run the whole
#                   stack in Docker (what you want to verify a deploy)
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File .\scripts\local.ps1 -Action infra
#   powershell -ExecutionPolicy Bypass -File .\scripts\local.ps1 -Action dev
#   powershell -ExecutionPolicy Bypass -File .\scripts\local.ps1 -Action app
#   powershell -ExecutionPolicy Bypass -File .\scripts\local.ps1 -Action status
#   powershell -ExecutionPolicy Bypass -File .\scripts\local.ps1 -Action logs -Service backend
#   powershell -ExecutionPolicy Bypass -File .\scripts\local.ps1 -Action down
#   powershell -ExecutionPolicy Bypass -File .\scripts\local.ps1 -Action clean
#
# Actions:
#   infra   start mysql/redis/rabbitmq/minio only (for IDE / mvn spring-boot:run)
#   dev     infra + open one window for the backend and one for the vite dev server
#   app     build jar + dist, build the artifact images, run everything in Docker
#   status  docker compose ps
#   logs    follow logs (optionally one service)
#   down    stop and remove containers, keep the data volumes
#   clean   stop and remove containers AND all local data volumes
[CmdletBinding()]
param(
    [ValidateSet('dev', 'infra', 'app', 'down', 'clean', 'status', 'logs')]
    [string]$Action = 'dev',

    [string]$Service,

    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $ProjectRoot

# The local compose file publishes the frontend on ${HTTP_PORT:-80}; 80 often clashes
# with IIS / other dev servers, so default the local stack to 8082.
if (-not $env:HTTP_PORT) { $env:HTTP_PORT = '8082' }

$ComposeBase = @('compose', '-f', 'docker-compose.yml')
$ComposeApp = @('compose', '-f', 'docker-compose.yml', '-f', 'docker-compose.jar.yml')

function Write-Step { param([string]$Text) Write-Host ""; Write-Host "==> $Text" -ForegroundColor Cyan }
function Write-Ok { param([string]$Text) Write-Host "    $Text" -ForegroundColor Green }
function Write-Note { param([string]$Text) Write-Host "    $Text" -ForegroundColor DarkGray }

function Assert-Docker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'docker CLI not found. Install Docker Desktop and reopen the terminal.'
    }
    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker is not reachable. Start Docker Desktop first, then re-run this script.'
    }
}

function Wait-Healthy {
    param([string]$Name, [int]$TimeoutSeconds = 180)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $status = (& docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $Name 2>$null)
        if ($status -eq 'healthy') { Write-Ok "$Name healthy"; return }
        Start-Sleep -Seconds 3
    }
    throw "$Name did not become healthy in $TimeoutSeconds seconds. Check: docker logs $Name"
}

function Wait-Running {
    param([string]$Name, [int]$TimeoutSeconds = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $running = (& docker inspect -f '{{.State.Running}}' $Name 2>$null)
        if ($running -eq 'true') { Write-Ok "$Name running"; return }
        Start-Sleep -Seconds 2
    }
    throw "$Name is not running. Check: docker logs $Name"
}

function Resolve-Tool {
    param([string[]]$Candidates)
    foreach ($candidate in $Candidates) {
        if (Get-Command $candidate -ErrorAction SilentlyContinue) { return $candidate }
    }
    return $null
}

function Invoke-Infra {
    Write-Step 'start local middleware (mysql / redis / rabbitmq / minio)'
    & docker @ComposeBase 'up' '-d' 'mysql' 'redis' 'rabbitmq' 'minio' 'minio-init'
    if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed.' }

    Wait-Healthy 'videonest-mysql' 180
    Wait-Healthy 'videonest-redis' 120
    Wait-Healthy 'videonest-rabbitmq' 180

    Write-Host ''
    Write-Ok 'mysql    : 127.0.0.1:3307  (db + user from .env)'
    Write-Ok 'redis    : 127.0.0.1:6380'
    Write-Ok 'rabbitmq : 127.0.0.1:5672  management http://127.0.0.1:15672'
    Write-Ok 'minio    : 127.0.0.1:9010  console   http://127.0.0.1:9011'
}

function Ensure-NodeModules {
    $frontend = Join-Path $ProjectRoot 'frontend'
    if (Test-Path (Join-Path $frontend 'node_modules\.bin\vite.cmd')) { return }

    Write-Step 'install frontend dependencies (first run only)'
    $npm = Resolve-Tool @('npm.cmd', 'npm')
    if (-not $npm) { throw 'npm not found in PATH.' }
    & $npm --prefix $frontend ci --no-audit --no-fund --registry https://registry.npmmirror.com
    if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' }
}

function Build-Backend {
    Write-Step 'build backend jar (mvn package -DskipTests)'
    $maven = Resolve-Tool @('mvn.cmd', 'mvn')
    if (-not $maven) {
        $wrapper = Join-Path $ProjectRoot 'backend\mvnw.cmd'
        if (Test-Path -LiteralPath $wrapper) { $maven = $wrapper }
    }
    if (-not $maven) { throw 'Maven not found. Install Maven or keep backend\mvnw.cmd.' }

    & $maven '-B' '-f' (Join-Path $ProjectRoot 'backend\pom.xml') '-DskipTests' 'package'
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    Write-Ok 'jar built'
}

function Build-Frontend {
    Write-Step 'build frontend dist (npm run build)'
    Ensure-NodeModules
    $npm = Resolve-Tool @('npm.cmd', 'npm')
    & $npm --prefix (Join-Path $ProjectRoot 'frontend') 'run' 'build'
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    Write-Ok 'dist built'
}

function Ensure-BaseImage {
    # A missing image is the normal first-run path. `docker image ls` returns an
    # empty result instead of writing an error, which keeps PowerShell's strict
    # native-command error handling from aborting the script.
    $baseImage = (& docker image ls 'videonest-backend-base:latest' '--format' '{{.Repository}}:{{.Tag}}' 2>$null | Out-String).Trim()
    if ($baseImage -eq 'videonest-backend-base:latest') { return }

    Write-Step 'build runtime base image (JRE + ffmpeg, one time)'
    & docker build '-f' 'backend/Dockerfile.base' '-t' 'videonest-backend-base:latest' 'backend'
    if ($LASTEXITCODE -ne 0) { throw 'Base image build failed.' }
}

function Assert-Artifacts {
    $jar = Get-ChildItem (Join-Path $ProjectRoot 'backend\target') -Filter '*.jar' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '(-sources|-javadoc|\.original)$' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw 'backend\target\*.jar not found. Run without -SkipBuild.' }
    if (-not (Test-Path (Join-Path $ProjectRoot 'frontend\dist'))) {
        throw 'frontend\dist not found. Run without -SkipBuild.'
    }
    return $jar
}

function Start-DevWindows {
    Write-Step 'open backend and frontend dev windows'

    $backendScript = Join-Path $ProjectRoot 'scripts\start-backend.ps1'
    if (-not (Test-Path -LiteralPath $backendScript)) { throw "Missing $backendScript" }

    Start-Process -FilePath 'powershell.exe' -ArgumentList @(
        '-NoExit', '-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', "`"$backendScript`""
    ) | Out-Null

    $viteCommand = "Set-Location -LiteralPath '$ProjectRoot\frontend'; npm run dev"
    Start-Process -FilePath 'powershell.exe' -ArgumentList @(
        '-NoExit', '-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-Command', "`"$viteCommand`""
    ) | Out-Null

    Write-Ok 'backend : http://127.0.0.1:8080  (spring-boot:run, profile dev)'
    Write-Ok 'frontend: http://127.0.0.1:5173  (vite dev server, /api proxied to 8080)'
    Write-Note 'Edit Java/Vue files and refresh the browser; no image rebuild needed.'
}

# ---------------------------------------------------------------------
# dispatch
# ---------------------------------------------------------------------
Assert-Docker

switch ($Action) {
    'infra' {
        Invoke-Infra
        Write-Host ''
        Write-Note 'Next: run the backend in your IDE (profile dev) and `npm run dev` in frontend/,'
        Write-Note 'or run this script with -Action dev to open both windows for you.'
    }

    'dev' {
        Invoke-Infra
        Ensure-NodeModules
        Start-DevWindows
    }

    'app' {
        if ($SkipBuild) {
            Write-Step 'reuse existing jar + dist (-SkipBuild)'
        }
        else {
            Build-Backend
            Build-Frontend
        }
        $null = Assert-Artifacts
        Ensure-BaseImage

        Write-Step 'build artifact images (jar + dist are only COPYed)'
        & docker @ComposeApp 'build' 'backend' 'frontend'
        if ($LASTEXITCODE -ne 0) { throw 'Image build failed.' }

        Write-Step 'start backend + frontend containers'
        & docker @ComposeApp 'up' '-d' 'backend' 'frontend'
        if ($LASTEXITCODE -ne 0) { throw 'docker compose up failed.' }

        Wait-Healthy 'videonest-backend' 180
        Wait-Running 'videonest-frontend' 60

        Write-Host ''
        Write-Ok "site    : http://127.0.0.1:$($env:HTTP_PORT)"
        Write-Ok 'api     : http://127.0.0.1:8080/api/videos/hot?limit=1'
        Write-Note 'This is the production shape: code changes need another -Action app.'
    }

    'status' {
        & docker @ComposeApp 'ps'
    }

    'logs' {
        if ($Service) {
            & docker @ComposeApp 'logs' '-f' '--tail=200' $Service
        }
        else {
            & docker @ComposeApp 'logs' '-f' '--tail=200'
        }
    }

    'down' {
        Write-Step 'stop local stack (data volumes are kept)'
        & docker @ComposeApp 'down' '--remove-orphans'
    }

    'clean' {
        Write-Host ''
        Write-Host 'This removes containers AND all local data volumes (mysql / redis / rabbitmq / minio).' -ForegroundColor Yellow
        $answer = Read-Host 'Type YES to continue'
        if ($answer -ne 'YES') {
            Write-Host 'Aborted.' -ForegroundColor Yellow
            return
        }
        & docker @ComposeApp 'down' '-v' '--remove-orphans'
        Write-Ok 'local data removed'
    }
}
