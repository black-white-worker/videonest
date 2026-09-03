# VideoNest 后端本地启动脚本
# 自动读取项目根目录 .env 文件设置环境变量，然后启动 Spring Boot

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$EnvFile = Join-Path $ProjectRoot ".env"

if (-not (Test-Path $EnvFile)) {
    Write-Error "未找到 .env 文件：$EnvFile"
    exit 1
}

Write-Host "正在加载 .env 环境变量..." -ForegroundColor Cyan
Get-Content $EnvFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
        $idx = $line.IndexOf("=")
        $key = $line.Substring(0, $idx).Trim()
        $value = $line.Substring($idx + 1).Trim()
        [Environment]::SetEnvironmentVariable($key, $value, "Process")
    }
}

Write-Host "环境变量加载完成，启动后端..." -ForegroundColor Green
Set-Location (Join-Path $ProjectRoot "backend")
mvn spring-boot:run
