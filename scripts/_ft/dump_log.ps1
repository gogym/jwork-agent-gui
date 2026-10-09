$ErrorActionPreference = 'SilentlyContinue'
$path = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
Write-Output ('MTS=' + (Get-Item $path).LastWriteTime)
Write-Output '----TAIL 160 (UTF8)----'
Get-Content -Path $path -Encoding UTF8 -Tail 160
