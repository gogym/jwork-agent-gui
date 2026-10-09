# 提取 CefPaintEvent / CefRenderer / CefBrowserOsr 相关源码
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = 'D:\Program Files\maven-mvnd-1.0.6-windows-amd64\m2\me\friwi\jcef-api\jcef-2caef5a+cef-141.0.10+g1d65b0d+chromium-141.0.7390.123\jcef-api-jcef-2caef5a+cef-141.0.10+g1d65b0d+chromium-141.0.7390.123-sources.jar'
$out = 'D:\GitHub\gim-agent-gui\scripts\_ft\out'
if (-not (Test-Path $out)) { New-Item -ItemType Directory -Path $out | Out-Null }
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
    foreach ($e in $zip.Entries) {
        if ($e.Name -match '^(CefPaintEvent|CefRenderer|CefBrowser_N)\.java$') {
            $dst = Join-Path $out ($e.Name)
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dst, $true)
            Write-Output ("EXTRACTED: " + $dst)
        }
    }
} finally { $zip.Dispose() }
