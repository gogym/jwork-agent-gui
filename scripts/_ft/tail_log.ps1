$ErrorActionPreference = 'SilentlyContinue'
$path = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
Write-Output ('MTS=' + (Get-Item $path).LastWriteTime)
Write-Output '----MATCHES----'
Get-Content -Path $path -Encoding UTF8 | Select-String -Pattern 'GIM_|JS console|onReady|onEmpty|OSR onPaint|realize|syncSize|NPE|Uncaught|TypeError|ReferenceError' | Select-Object -Last 50
Write-Output '----END----'
