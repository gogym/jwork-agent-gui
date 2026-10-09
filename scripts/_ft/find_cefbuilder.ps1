# 从本地 m2 仓库提取 jcefmaven 的 CefAppBuilder.java / jcef-api 的 CefApp.java，
# 验证 addJcefArgs 的命令行参数是否真正传入 CEF（--disable-background-networking 生效性核查）
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$m2 = 'D:\Program Files\maven-mvnd-1.0.6-windows-amd64\m2\me\friwi'
$out = 'D:\GitHub\gim-agent-gui\scripts\_ft\out'
$result = 'D:\GitHub\gim-agent-gui\scripts\_ft\out\cefbuilder.txt'
Remove-Item $result -ErrorAction SilentlyContinue
$foundAny = $false
Get-ChildItem -Path $m2 -Recurse -Filter '*-sources.jar' | ForEach-Object {
    $jarPath = $_.FullName
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        foreach ($e in $zip.Entries) {
            if ($e.Name -ne 'CefAppBuilder.java' -and $e.Name -ne 'CefApp.java') { continue }
            $foundAny = $true
            $reader = New-Object System.IO.StreamReader($e.Open())
            try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
            $dst = Join-Path $out $e.Name
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dst, $true)
            "JAR: $jarPath" | Out-File $result -Append -Encoding UTF8
            "ENTRY: $($e.FullName) -> $dst" | Out-File $result -Append -Encoding UTF8
            $lines = $text -split "`n"
            for ($i = 0; $i -lt $lines.Count; $i++) {
                if ($lines[$i] -match 'addJcefArgs|commandLineArgs|startup|getInstance|argsFor|setJcefArgs|String\[\] args|List<String>') {
                    "  L$($i+1): $($lines[$i].Trim())" | Out-File $result -Append -Encoding UTF8
                }
            }
            "-----" | Out-File $result -Append -Encoding UTF8
        }
    } finally { $zip.Dispose() }
}
if (-not $foundAny) { 'NO SOURCES JAR HIT' | Out-File $result -Append -Encoding UTF8 }
Write-Output "DONE found=$foundAny"
