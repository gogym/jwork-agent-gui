# Jwork Windows 打包脚本（M5：jpackage）
# 用法:
#   powershell -ExecutionPolicy Bypass -File .\scripts\package-windows.ps1 [-Type msi|app-image]
# 依赖:
#   - Liberica JDK 21（含 jpackage）
#   - 出 .msi 需安装 WiX Toolset v3（candle.exe/light.exe 在 PATH）；未装则回退 app-image。
#   - msi 产物配合应用内三段式更新（检查 → SHA256 下载校验 → msiexec /qn 静默安装）。
param(
    [ValidateSet("msi", "app-image")]
    [string]$Type = "app-image"
)

$ErrorActionPreference = "Stop"
$AppName = "Jwork"
$MainClass = "com.gettyio.gim.agent.ui.Launcher"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$UiDir = Join-Path $RepoRoot "gim-agent-ui"
$TargetDir = Join-Path $UiDir "target"
$LibDir = Join-Path $TargetDir "lib"
$StageDir = Join-Path $TargetDir "package"
$OutDir = Join-Path $RepoRoot "build\installer"

Write-Host "== 1/4 Maven 打包 (-Ppackage) ==" -ForegroundColor Cyan
Push-Location $RepoRoot
try {
    & mvn -q -Ppackage clean package -pl gim-agent-ui -am
    if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败" }
} finally {
    Pop-Location
}

# 版本号单一来源：从构建产物中 Maven 过滤后的 version.properties 读取，去掉 -SNAPSHOT 供 jpackage 使用
$VersionFile = Join-Path $TargetDir "classes\version.properties"
if (-not (Test-Path $VersionFile)) { throw "未找到过滤后的 version.properties: $VersionFile" }
$RawVersion = (Get-Content $VersionFile | Where-Object { $_ -match '^app\.version=' } | Select-Object -First 1) -replace '^app\.version=', ''
$AppVersion = ($RawVersion -replace '-SNAPSHOT$', '').Trim()
if (-not $AppVersion) { throw "无法从 version.properties 解析版本号" }
Write-Host ("应用版本: " + $AppVersion)

Write-Host "== 2/4 组装扁平 classpath 暂存目录 ==" -ForegroundColor Cyan
if (Test-Path $StageDir) { Remove-Item $StageDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $StageDir "lib") | Out-Null
$MainJar = Get-ChildItem -Path $TargetDir -Filter "gim-agent-ui-*.jar" |
    Where-Object { $_.Name -notmatch "sources|javadoc" } | Select-Object -First 1
if (-not $MainJar) { throw "未找到主 jar: $TargetDir\gim-agent-ui-*.jar" }
Copy-Item $MainJar.FullName -Destination (Join-Path $StageDir $MainJar.Name)
Copy-Item (Join-Path $LibDir "*") -Destination (Join-Path $StageDir "lib") -Recurse
Write-Host ("主 jar: " + $MainJar.Name)

Write-Host "== 3/4 选择打包类型 ==" -ForegroundColor Cyan
$WixOk = $false
$candle = Get-Command candle.exe -ErrorAction SilentlyContinue
if ($Type -eq "msi") {
    if ($candle) { $WixOk = $true }
    else { Write-Warning "未检测到 WiX Toolset，回退到 app-image" }
}
$FinalType = if ($Type -eq "msi" -and $WixOk) { "msi" } else { "app-image" }

Write-Host "== 4/4 jpackage ($FinalType) ==" -ForegroundColor Cyan
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force -Path $OutDir | Out-Null }
$jpackage = Join-Path $env:JAVA_HOME "bin\jpackage.exe"
if (-not (Test-Path $jpackage)) { $jpackage = "jpackage" }

# msi 附加开始菜单与桌面快捷方式（仅 msi 生效；app-image 不传避免 jpackage 告警）
$MsiExtras = @()
if ($FinalType -eq "msi") {
    $MsiExtras = @("--win-menu", "--win-shortcut")
}

& $jpackage `
    --type $FinalType `
    --name $AppName `
    --app-version $AppVersion `
    --input $StageDir `
    --main-jar $MainJar.Name `
    --main-class $MainClass `
    @MsiExtras `
    --java-options "-Xmx512m" `
    --java-options "-Dfile.encoding=UTF-8" `
    --java-options "--add-exports=java.base/java.lang=ALL-UNNAMED" `
    --java-options "--add-exports=java.desktop/sun.awt=ALL-UNNAMED" `
    --java-options "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED" `
    --java-options "--add-opens=java.desktop/sun.awt=ALL-UNNAMED" `
    --dest $OutDir

if ($LASTEXITCODE -ne 0) { throw "jpackage 执行失败" }
Write-Host "打包完成，产物位于: $OutDir" -ForegroundColor Green
