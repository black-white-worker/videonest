param(
    [string]$Server = "82.157.205.6",
    [string]$RemoteUser = "ubuntu",
    [string]$RemoteDir = "/opt/videonest",
    [string]$IdentityFile,
    [string]$PublicSiteUrl,
    [switch]$SkipBuild,
    [switch]$SkipTests,
    [switch]$SkipMigrations
)

$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Archive = Join-Path $ProjectRoot "videonest-deploy.tar.gz"
$EnvFile = Join-Path $ProjectRoot ".env"
$Target = "${RemoteUser}@${Server}"
$SshArgs = @()
$Sudo = if ($RemoteUser -eq "root") { "" } else { "sudo " }
$Compose = "${Sudo}docker compose -f docker-compose.yml -f docker-compose.jar.yml"

if ($IdentityFile) {
    $ResolvedIdentity = (Resolve-Path $IdentityFile).Path
    $SshArgs += @("-i", $ResolvedIdentity)
}

if (-not (Test-Path $EnvFile)) {
    throw "Missing $EnvFile. Create it and fill production secrets first."
}

$MinioPublicEndpoint = Get-Content $EnvFile | ForEach-Object {
    if ($_ -match '^MINIO_PUBLIC_ENDPOINT=(.+)$') { $matches[1].Trim() }
} | Select-Object -First 1

if (-not $MinioPublicEndpoint) {
    throw "MINIO_PUBLIC_ENDPOINT is missing from $EnvFile. Set it to a browser-accessible MinIO address."
}

if ($MinioPublicEndpoint -match '^https?://(127\.0\.0\.1|localhost)(:\d+)?(/|$)' -and
    $Server -notin @('127.0.0.1', 'localhost')) {
    throw "MINIO_PUBLIC_ENDPOINT cannot use localhost for remote deployment. Example: http://$Server`:9000"
}

if (-not $PublicSiteUrl) {
    $PublicSiteUrl = "http://$Server"
}

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot "package-deploy.ps1") `
        -OutputPath $Archive `
        -PublicSiteUrl $PublicSiteUrl `
        -SkipTests:$SkipTests
    if ($LASTEXITCODE -ne 0) { throw "Local package failed" }
} elseif (-not (Test-Path $Archive)) {
    throw "Missing $Archive; run without -SkipBuild first."
}

Write-Host "Preparing $Target`:$RemoteDir ..."
& ssh @SshArgs $Target "${Sudo}mkdir -p '$RemoteDir' && ${Sudo}chown '$RemoteUser' '$RemoteDir'"
if ($LASTEXITCODE -ne 0) { throw "Unable to prepare remote directory" }

& scp @SshArgs $Archive "${Target}:/tmp/videonest-deploy.tar.gz"
if ($LASTEXITCODE -ne 0) { throw "Archive upload failed" }

& scp @SshArgs $EnvFile "${Target}:/tmp/videonest.env"
if ($LASTEXITCODE -ne 0) { throw "Environment upload failed" }

$MigrationCommand = ""
if (-not $SkipMigrations) {
    $MigrationCommand = "$Compose up -d mysql; $Compose exec -T mysql sh -c 'until mysqladmin ping -h localhost -u root -p`"`$MYSQL_ROOT_PASSWORD`" --silent; do sleep 2; done; exec mysql -u root -p`"`$MYSQL_ROOT_PASSWORD`" `"`$MYSQL_DATABASE`"' < sql/2026-08-11-add-comment-root-id.sql; "
}

$RemoteCommand = "set -e; tar -xzf /tmp/videonest-deploy.tar.gz -C '$RemoteDir'; install -m 600 /tmp/videonest.env '$RemoteDir/.env'; cd '$RemoteDir'; $MigrationCommand$Compose build backend frontend; $Compose up -d --no-build --remove-orphans; $Compose ps; rm -f /tmp/videonest-deploy.tar.gz /tmp/videonest.env"
& ssh @SshArgs $Target $RemoteCommand
if ($LASTEXITCODE -ne 0) { throw "Remote deployment failed" }

Write-Host "Deployment complete: $PublicSiteUrl"
