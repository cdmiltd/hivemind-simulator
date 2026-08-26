# ========================================
# DJI Dock 模拟器 Docker 一键构建脚本
# ========================================
# 用法：.\docker-build.ps1
# 前置条件：
#   1. 已安装 Docker Desktop
#   2. SDK 已打入 lib/ 目录，无需 mvn install
# ========================================

param(
    [switch]$SkipTest,
    [switch]$Rebuild,
    [switch]$NoStart
)

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  DJI Dock 模拟器 Docker 构建" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host ""

# Step 1: 构建 jar
Write-Host "[1/3] 构建 Maven jar..." -ForegroundColor Yellow

$mvnArgs = @("package")
if ($SkipTest) {
    $mvnArgs += "-DskipTests"
}

& mvn $mvnArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host "Maven 构建失败，请检查 lib/ 目录下 SDK jar 是否存在" -ForegroundColor Red
    exit 1
}
Write-Host "Maven 构建成功" -ForegroundColor Green
Write-Host ""

# 提取 pom.xml 根元素 project/version(非 parent/version),注入镜像 LABEL version
# XPath 用 local-name() 规避 pom 默认命名空间导致的匹配失败
$appVersion = (Select-Xml -XPath "/*[local-name()='project']/*[local-name()='version']" -Path pom.xml).Node.InnerText
$env:APP_VERSION = $appVersion
Write-Host "镜像版本号：$appVersion（来源 pom.xml，注入 LABEL version）" -ForegroundColor Cyan

# 清理旧版本 jar:Dockerfile 的 COPY glob(dji-dock-simulator-*.jar)匹配多版本时行为不确定,
# 曾导致旧代码混入镜像(2026-08-20 中午事故根因)。仅保留 pom 当前版本的单一 jar,
# 并刷新 Tauri 固定名 stage jar(与 release.yml 的 Stage JAR 步骤对齐)
Get-ChildItem "target/dji-dock-simulator-*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -ne "dji-dock-simulator-$appVersion.jar" } |
    Remove-Item -Force
Copy-Item "target/dji-dock-simulator-$appVersion.jar" "target/dji-dock-simulator.jar" -Force
Write-Host ""

# Step 2: 构建 Docker 镜像
Write-Host "[2/3] 构建 Docker 镜像..." -ForegroundColor Yellow

$composeArgs = @("build")
if ($Rebuild) {
    $composeArgs += "--no-cache"
}

& docker compose $composeArgs
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker 构建失败" -ForegroundColor Red
    exit 1
}
Write-Host "Docker 镜像构建成功" -ForegroundColor Green
Write-Host ""

# Step 3: 启动服务
if (-not $NoStart) {
    Write-Host "[3/3] 启动 Docker 服务..." -ForegroundColor Yellow

    & docker compose up -d
    if ($LASTEXITCODE -ne 0) {
        Write-Host "Docker 启动失败" -ForegroundColor Red
        exit 1
    }

    Write-Host ""
    Write-Host "=========================================" -ForegroundColor Green
    Write-Host "  部署完成！" -ForegroundColor Green
    Write-Host "=========================================" -ForegroundColor Green
    Write-Host ""
    Write-Host "  模拟器：        http://localhost:9090" -ForegroundColor White
    Write-Host "  EMQX Dashboard：http://localhost:18083 (admin/public)" -ForegroundColor White
    Write-Host ""
    Write-Host "  查看日志：  docker compose logs -f simulator" -ForegroundColor Gray
    Write-Host "  停止服务：  docker compose down" -ForegroundColor Gray
    Write-Host ""
} else {
    Write-Host "跳过启动（-NoStart）" -ForegroundColor Gray
    Write-Host "手动启动：docker compose up -d" -ForegroundColor Gray
}
