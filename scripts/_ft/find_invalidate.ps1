# 在 java-cef sources jar 里查找 invalidate 相关 API（CefBrowserHost::Invalidate 的 Java 映射）
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = 'D:\Program Files\maven-mvnd-1.0.6-windows-amd64\m2\me\friwi\jcef-api\jcef-2caef5a+cef-141.0.10+g1d65b0d+chromium-141.0.7390.123\jcef-api-jcef-2caef5a+cef-141.0.10+g1d65b0d+chromium-141.0.7390.123-sources.jar'
$out = 'D:\GitHub\gim-agent-gui\scripts\_ft\out'
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
    foreach ($e in $zip.Entries) {
        if (-not $e.Name.EndsWith('.java')) { continue }
        $reader = New-Object System.IO.StreamReader($e.Open())
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($text -match 'nvalidate') {
            $dst = Join-Path $out ('inv_' + $e.Name)
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dst, $true)
            $hits = ($text -split "`n" | Select-String -Pattern 'nvalidate') | Select-Object -First 8
            Write-Output ("HIT: " + $e.FullName)
            foreach ($h in $hits) { Write-Output ("    L" + $h.LineNumber + ": " + $h.Line.Trim()) }
        }
    }
} finally { $zip.Dispose() }
