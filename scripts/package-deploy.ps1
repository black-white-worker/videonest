param(
    [string]$OutputPath = ".\videonest-deploy.tar.gz",
    [string]$PublicSiteUrl,
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

if ($PublicSiteUrl) {
    $env:VITE_PUBLIC_SITE_URL = $PublicSiteUrl
}

Push-Location $ProjectRoot
try {
    $Maven = if (Get-Command mvn.cmd -ErrorAction SilentlyContinue) { "mvn.cmd" } else { "mvn" }
    $MavenArgs = @("-f", ".\backend\pom.xml", "clean", "package")
    if ($SkipTests) { $MavenArgs += "-DskipTests" }
    & $Maven @MavenArgs
    if ($LASTEXITCODE -ne 0) { throw "Backend package failed" }

    if (-not (Test-Path .\frontend\node_modules\.bin\vite.cmd)) {
        $NpmCache = Join-Path $ProjectRoot "tmp\npm-cache"
        npm.cmd --prefix .\frontend --cache $NpmCache ci
        if ($LASTEXITCODE -ne 0) { throw "Frontend dependency install failed" }
    }

    npm.cmd --prefix .\frontend run build
    if ($LASTEXITCODE -ne 0) { throw "Frontend build failed" }

    $ResolvedOutputPath = [System.IO.Path]::GetFullPath($OutputPath)
    tar.exe -czf $ResolvedOutputPath `
        backend\target\*.jar `
        backend\Dockerfile.jar `
        frontend\dist `
        frontend\Dockerfile.dist `
        frontend\nginx.conf `
        deploy\rabbitmq\Dockerfile `
        deploy\minio\cors.xml `
        sql `
        scripts\check-media-delivery.js `
        docker-compose.yml `
        docker-compose.jar.yml
    if ($LASTEXITCODE -ne 0) { throw "Deployment archive failed" }

    Write-Host "Deployment archive created: $ResolvedOutputPath"
} finally {
    Pop-Location
}
