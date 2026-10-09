# 提取 CefAppHandlerAdapter.java 与 CefSettings.java：
# 1) 验证 args 注入 CEF 命令行的机制（onBeforeCommandLineProcessing）；
# 2) 确认 LogSeverity 枚举成员名与 DISABLE 语义（用于日志抑制方案）。
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = 'D:\Program Files\maven-mvnd-1.0.6-windows-amd64\m2\me\friwi\jcef-api\jcef-2caef5a+cef-141.0.10+g1d65b0d+chromium-141.0.7390.123\jcef-api-jcef-2caef5a+cef-141.0.10+g1d65b0d+chromium-141.0.7390.123-sources.jar'
$out = 'D:\GitHub\gim-agent-gui\scripts\_ft\out'
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
    foreach ($e in $zip.Entries) {
        if ($e.Name -ne 'CefAppHandlerAdapter.java' -and $e.Name -ne 'CefSettings.java') { continue }
        $dst = Join-Path $out $e.Name
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dst, $true)
        Write-Output "HIT $($e.FullName) -> $dst"
    }
} finally { $zip.Dispose() }
Write-Output '--- CefAppHandlerAdapter: commandLine/args 相关行 ---'
Select-String -Path (Join-Path $out 'CefAppHandlerAdapter.java') -Pattern 'commandLine|args_|append|String\[\]' | ForEach-Object { 'L' + $_.LineNumber + ': ' + $_.Line.Trim() }
Write-Output '--- CefSettings: LogSeverity 枚举块 ---'
$lines = Get-Content (Join-Path $out 'CefSettings.java')
$in = $false
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match 'enum LogSeverity') { $in = $true }
    if ($in) { Write-Output ('L' + ($i + 1) + ': ' + $lines[$i].Trim()) }
    if ($in -and $lines[$i] -match '^\s*\}\s*$') { break }
}
