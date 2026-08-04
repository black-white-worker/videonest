param(
    [string]$OutputPath = ".\videonest-deploy.tar.gz",
    [string]$PublicSiteUrl
)

$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

if ($PublicSiteUrl) {
    $env:VITE_PUBLIC_SITE_URL = $PublicSiteUrl
}

Push-Location $ProjectRoot
try {
    mvn -f .\backend\pom.xml clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Backend package failed" }

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
        docker-compose.jar.yml `
        .env.example
    if ($LASTEXITCODE -ne 0) { throw "Deployment archive failed" }

    Write-Host "Deployment archive created: $ResolvedOutputPath"
} finally {
    Pop-Location
}
