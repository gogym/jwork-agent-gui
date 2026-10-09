# 提取 jcefmaven 的 CefInitializer.java，确认 jcefArgs 如何传入 CEF 初始化
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = 'D:\Program Files\maven-mvnd-1.0.6-windows-amd64\m2\me\friwi\jcefmaven\141.0.10\jcefmaven-141.0.10-sources.jar'
$out = 'D:\GitHub\gim-agent-gui\scripts\_ft\out\CefInitializer.java'
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
    foreach ($e in $zip.Entries) {
        if ($e.Name -ne 'CefInitializer.java') { continue }
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $out, $true)
        Write-Output "HIT $($e.FullName) -> $out"
    }
} finally { $zip.Dispose() }
