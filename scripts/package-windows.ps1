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
$MainClass = "com.gettyio.jwork.agent.ui.Launcher"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$UiDir = Join-Path $RepoRoot "jwork-agent-ui"
$TargetDir = Join-Path $UiDir "target"
$LibDir = Join-Path $TargetDir "lib"
$StageDir = Join-Path $TargetDir "package"
$OutDir = Join-Path $RepoRoot "build\installer"

Write-Host "== 1/4 Maven 打包 (-Ppackage) ==" -ForegroundColor Cyan
Push-Location $RepoRoot
try {
    & mvn -q -Ppackage clean package -pl jwork-agent-ui -am
    if ($LASTEXITCODE -ne 0) { throw "Maven 构建失败" }
} finally {
    Pop-Location
}

# 版本号单一来源：从构建产物中 Maven 过滤后的 version.properties 读取，去掉 -SNAPSHOT 供 jpackage 使用
$VersionFile = Join-Path $TargetDir "classes\version.properties"
if (-not (Test-Path $VersionFile)) { throw "未找到过滤后的 version.properties: $VersionFile" }
# 显式 UTF-8 读取（文件含中文注释无 BOM，PS 5.1 默认按 ANSI 读会乱码致行匹配失败）；@(...)[0] 强制取首匹配行
$verLine = @(Get-Content $VersionFile -Encoding UTF8 | Where-Object { $_ -match '^app\.version=' })[0]
$AppVersion = (($verLine -replace '^app\.version=', '') -replace '-SNAPSHOT$', '').Trim()
if (-not $AppVersion) { throw "无法从 version.properties 解析版本号" }
Write-Host ("应用版本: " + $AppVersion)

Write-Host "== 2/4 组装扁平 classpath 暂存目录 ==" -ForegroundColor Cyan
if (Test-Path $StageDir) { Remove-Item $StageDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $StageDir "lib") | Out-Null
$MainJar = Get-ChildItem -Path $TargetDir -Filter "jwork-agent-ui-*.jar" |
    Where-Object { $_.Name -notmatch "sources|javadoc" } | Select-Object -First 1
if (-not $MainJar) { throw "未找到主 jar: $TargetDir\jwork-agent-ui-*.jar" }
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

# jpackage 不覆盖已存在的产物（目标已存在即报错）：重复打包前清理旧产物
# （应用需已退出，否则 Jwork.exe 被占用会清理失败）
$OldAppDir = Join-Path $OutDir $AppName
if (Test-Path $OldAppDir) {
    Write-Host ("清理旧 app-image 目录: " + $OldAppDir) -ForegroundColor Yellow
    Remove-Item -Recurse -Force $OldAppDir
}
$OldMsi = Join-Path $OutDir ($AppName + "-" + $AppVersion + ".msi")
if (Test-Path $OldMsi) {
    Write-Host ("清理旧 msi: " + $OldMsi) -ForegroundColor Yellow
    Remove-Item -Force $OldMsi
}

$jpackage = Join-Path $env:JAVA_HOME "bin\jpackage.exe"
if (-not (Test-Path $jpackage)) { $jpackage = "jpackage" }

# msi 保留中间产物：自定义三语向导与扩展库同 Id 对话框撞车（LGHT0091），jpackage 内置 light
# 必然失败；失败后剥离 wixobj 中的库引用再手动重跑 light（见下方 4.1 步）。
$TempDir = Join-Path $RepoRoot "build\jp-temp"
$TempArgs = @()
if ($FinalType -eq "msi") {
    if (Test-Path $TempDir) { Remove-Item $TempDir -Recurse -Force }
    $TempArgs = @("--temp", $TempDir)
}

# msi 附加开始菜单与桌面快捷方式（仅 msi 生效；app-image 不传避免 jpackage 告警）；
# 自定义中文向导（scripts\wix）：许可证/安装位置/数据位置/快捷方式勾选页，
# 数据目录写 HKCU 注册表供应用 JworkPaths.defaultRoot 读取；
# 升级码钉死为已发布 1.0.0 msi 的 UpgradeCode，保证静默升级正确替换旧版。
# license.rtf 须经 --license-file 传入（jpackage 不从 resource-dir 拷贝它），
# 落入 config 后由自定义向导的 WixUILicenseRtf 引用。
$MsiExtras = @()
if ($FinalType -eq "msi") {
    $MsiExtras = @(
        "--win-menu", "--win-shortcut", "--win-shortcut-prompt",
        "--win-upgrade-uuid", "9269FEA8-6FD8-3D21-9494-2C39EE29EE2D",
        "--license-file", (Join-Path $RepoRoot "scripts\wix\license.rtf"),
        "--resource-dir", (Join-Path $RepoRoot "scripts\wix")
    )
}

# 应用图标（多尺寸 ICO：exe 与 msi 快捷方式共用）：从源码资源目录直取，随包维护同一份
$IconFile = Join-Path $UiDir "src\main\resources\icons\jwork.ico"
if (-not (Test-Path $IconFile)) { throw "未找到应用图标: $IconFile" }

& $jpackage `
    --type $FinalType `
    --name $AppName `
    --app-version $AppVersion `
    --input $StageDir `
    --main-jar $MainJar.Name `
    --main-class $MainClass `
    --icon $IconFile `
    @MsiExtras `
    @TempArgs `
    --java-options "-Xmx512m" `
    --java-options "-Dfile.encoding=UTF-8" `
    --java-options "--add-exports=java.base/java.lang=ALL-UNNAMED" `
    --java-options "--add-exports=java.desktop/sun.awt=ALL-UNNAMED" `
    --java-options "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED" `
    --java-options "--add-opens=java.desktop/sun.awt=ALL-UNNAMED" `
    --dest $OutDir

if ($LASTEXITCODE -ne 0) {
    if ($FinalType -ne "msi") { throw "jpackage 执行失败" }
    # 4.1 自定义向导重链：WiX3 linker 解析引用优先扩展库 fragment，candle 产出的
    # Dialog / 对话框 Show / WixUIRMOption 引用会把库版对话框拉入与本地同 Id 定义撞车。
    # 24 页已全部在 main.wxs 的 Product 内定义（ShortcutPromptDlg 经 DialogRef 链入本地
    # 覆盖 fragment，扩展库无同 Id 符号须保留），剥离这些引用后手动重跑 light。
    $WixObjDir = Join-Path $TempDir "wixobj"
    if (-not (Test-Path (Join-Path $WixObjDir "main.wixobj"))) { throw "jpackage 执行失败（未到 light 阶段，无 wixobj 可重链）" }
    Write-Host "== 4.1 剥离库引用并重跑 light ==" -ForegroundColor Cyan
    foreach ($f in @("main", "ui", "ShortcutPromptDlg", "bundle")) {
        $p = Join-Path $WixObjDir ($f + ".wixobj")
        if (-not (Test-Path $p)) { continue }
        $t = [IO.File]::ReadAllText($p)
        $t = [regex]::Replace($t, '<row[^>]*><field>Dialog</field><field>(?!ShortcutPromptDlg</field>)[^<]*</field></row>', '')
        $t = [regex]::Replace($t, '<row[^>]*><field>WixAction</field><field>InstallUISequence/(PrepareDlg|ProgressDlg|WelcomeDlg|MaintenanceWelcomeDlg|ResumeDlg|ExitDialog|UserExit|FatalError|JworkDataDlg|ShortcutPromptDlg)</field></row>', '')
        $t = [regex]::Replace($t, '<row[^>]*><field>Property</field><field>WixUIRMOption</field></row>', '')
        [IO.File]::WriteAllText($p, $t)
    }
    $ConfigDir = Join-Path $TempDir "config"
    # jpackage 的 resource-dir 只拷认识的资源类型（wxs/wxl/rtf），lang.js 与品牌 bmp
    # 不会被拷入 config；light 按 -b config 解析 SourceFile，此处手动补拷。
    foreach ($extra in @("lang.js", "banner.bmp", "dialog.bmp", "license-zh-cn.rtf", "license-zh-tw.rtf", "license-en.rtf")) {
        $src = Join-Path $RepoRoot ("scripts\wix\" + $extra)
        if (Test-Path $src) { Copy-Item $src (Join-Path $ConfigDir $extra) -Force }
    }
    $LightArgs = @(
        "-nologo", "-spdb", "-ext", "WixUtilExtension",
        "-out", $OldMsi,
        "-ext", "WixUIExtension",
        "-b", $ConfigDir,
        "-sice:ICE27"
    )
    foreach ($wxl in (Get-ChildItem $ConfigDir -Filter "MsiInstallerStrings_*.wxl" | Sort-Object Name)) {
        $LightArgs += @("-loc", $wxl.FullName)
    }
    $LightArgs += "-cultures:zh-cn"
    foreach ($f in @("main", "bundle", "ui", "ShortcutPromptDlg")) {
        $LightArgs += (Join-Path $WixObjDir ($f + ".wixobj"))
    }
    # 与 jpackage 内置 light 相同的工作目录（相对路径解析一致）
    Push-Location (Join-Path $TempDir "images\win-msi.image\$AppName")
    try {
        & light.exe @LightArgs
        if ($LASTEXITCODE -ne 0) { throw "light 重链失败" }
    } finally {
        Pop-Location
    }
}
Write-Host "打包完成，产物位于: $OutDir" -ForegroundColor Green
