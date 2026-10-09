$m2 = 'D:\Program Files\maven-mvnd-1.0.6-windows-amd64\m2'
$jar = Get-ChildItem -Path $m2 -Recurse -Filter 'javafx-graphics-*-sources.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jar) { Write-Host 'NO_SOURCES_JAR'; exit 1 }
Write-Host ('JAR=' + $jar.FullName)

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
$entry = $zip.Entries | Where-Object { $_.FullName -like '*GlassViewEventHandler.java' } | Select-Object -First 1
if (-not $entry) { Write-Host 'NO_ENTRY'; $zip.Dispose(); exit 1 }
Write-Host ('ENTRY=' + $entry.FullName)

$out = 'D:\GitHub\gim-agent-gui\scripts\_ft\GlassViewEventHandler.java.txt'
$sr = New-Object System.IO.StreamReader($entry.Open())
$text = $sr.ReadToEnd()
$sr.Close()
$zip.Dispose()
Set-Content -Path $out -Value $text -Encoding UTF8
Write-Host ('WROTE=' + $out + ' len=' + $text.Length)
