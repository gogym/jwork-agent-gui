$ErrorActionPreference = 'SilentlyContinue'
$dir = Join-Path $env:TEMP 'jwork\cef-web\chat'
Write-Output ('DIR=' + $dir)
if (Test-Path $dir) {
    Get-ChildItem $dir | ForEach-Object { Write-Output ($_.Name + '  ' + $_.Length + '  ' + $_.LastWriteTime) }
    Write-Output '----index.html----'
    Get-Content -Path (Join-Path $dir 'index.html') -Encoding UTF8
    Write-Output '----chat.js first 6 lines----'
    Get-Content -Path (Join-Path $dir 'chat.js') -Encoding UTF8 -TotalCount 6
    Write-Output '----chat.js last 4 lines----'
    Get-Content -Path (Join-Path $dir 'chat.js') -Encoding UTF8 -Tail 4
} else {
    Write-Output 'DIR NOT FOUND'
}
