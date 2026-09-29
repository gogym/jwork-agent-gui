# Gim Agent Windows 打包脚本（M5：jpackage）
# 用法:
#   powershell -ExecutionPolicy Bypass -File .\scripts\package-windows.ps1 [-Type msi|app-image]
# 依赖:
#   - Liberica JDK 17（含 jpackage）
#   - 出 .msi 需安装 WiX Toolset v3（candle.exe/light.exe 在 PATH）；未装则回退 app-image。
param(
    [ValidateSet("msi", "app-image")]
    [string]$Type = "app-image"
)

$ErrorActionPreference = "Stop"
$AppName = "Gim Agent"
$AppVersion = "1.0.0"
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
    & mvn -q -Ppackage -DskipTests clean package -pl gim-agent-ui -am
    if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败" }
} finally {
    Pop-Location
}

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

& $jpackage `
    --type $FinalType `
    --name $AppName `
    --app-version $AppVersion `
    --input $StageDir `
    --main-jar $MainJar.Name `
    --main-class $MainClass `
    --java-options "-Xmx512m" `
    --java-options "-Dfile.encoding=UTF-8" `
    --dest $OutDir

if ($LASTEXITCODE -ne 0) { throw "jpackage 执行失败" }
Write-Host "打包完成，产物位于: $OutDir" -ForegroundColor Green
