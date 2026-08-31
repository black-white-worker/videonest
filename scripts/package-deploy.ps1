param(
    [string]$OutputPath = ".\videonest-deploy.tar.gz",
    [string]$PublicSiteUrl,
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$PreviousPublicSiteUrl = [Environment]::GetEnvironmentVariable("VITE_PUBLIC_SITE_URL", "Process")

function Get-TestFailureSummary {
    $ReportDirectory = Join-Path $ProjectRoot "backend\target\surefire-reports"
    if (-not (Test-Path -LiteralPath $ReportDirectory)) {
        return @("Surefire 报告目录不存在：$ReportDirectory")
    }

    $Summary = @()
    foreach ($Report in Get-ChildItem -LiteralPath $ReportDirectory -Filter "TEST-*.xml" -File) {
        try {
            [xml]$Xml = Get-Content -LiteralPath $Report.FullName -Raw
            foreach ($Case in $Xml.testsuite.testcase) {
                if ($Case.failure -or $Case.error) {
                    $Problem = if ($Case.error) { $Case.error } else { $Case.failure }
                    $Message = [string]$Problem.message
                    if ([string]::IsNullOrWhiteSpace($Message)) { $Message = [string]$Problem.'#text' }
                    $Summary += "{0}.{1}: {2}" -f $Case.classname, $Case.name, $Message
                }
            }
        } catch {
            $Summary += "无法读取测试报告 $($Report.Name)：$($_.Exception.Message)"
        }
    }
    if ($Summary.Count -eq 0) { $Summary += "未能从 Surefire XML 报告提取具体失败用例。" }
    return $Summary
}

if ($PublicSiteUrl) {
    $env:VITE_PUBLIC_SITE_URL = $PublicSiteUrl
}

Push-Location $ProjectRoot
try {
    $MavenWrapper = Join-Path $ProjectRoot "backend\mvnw.cmd"
    if (Get-Command mvn.cmd -ErrorAction SilentlyContinue) {
        $Maven = "mvn.cmd"
    } elseif (Get-Command mvn -ErrorAction SilentlyContinue) {
        $Maven = "mvn"
    } elseif (Test-Path -LiteralPath $MavenWrapper) {
        $Maven = $MavenWrapper
    } else {
        throw "找不到 Maven。请安装 Maven，或保留 backend\mvnw.cmd。"
    }
    $MavenArgs = @("-f", ".\backend\pom.xml", "clean", "package")
    if ($SkipTests) { $MavenArgs += "-DskipTests" }
    if ($SkipTests) {
        Write-Warning "已跳过后端测试；这只适合应急部署。正常部署请移除 -SkipTests。"
    }
    & $Maven @MavenArgs
    if ($LASTEXITCODE -ne 0) {
        if (-not $SkipTests) {
            Write-Host "后端测试或打包失败。失败用例：" -ForegroundColor Red
            Get-TestFailureSummary | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
            Write-Host "如果失败原因只是本机未启动 MySQL/Redis/Docker，可确认风险后使用 -SkipTests 应急打包。" -ForegroundColor Yellow
        }
        throw "Backend package failed (exit code $LASTEXITCODE)"
    }

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
        docker-compose.yml `
        docker-compose.jar.yml
    if ($LASTEXITCODE -ne 0) { throw "Deployment archive failed" }

    Write-Host "Deployment archive created: $ResolvedOutputPath"
} finally {
    Pop-Location
    if ($null -eq $PreviousPublicSiteUrl) {
        Remove-Item Env:\VITE_PUBLIC_SITE_URL -ErrorAction SilentlyContinue
    } else {
        $env:VITE_PUBLIC_SITE_URL = $PreviousPublicSiteUrl
    }
}
