$ErrorActionPreference = 'Continue'
$log = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
Write-Host '--- startup + size related lines (last 3000 lines) ---'
$lines = Get-Content $log -Tail 3000 -Encoding UTF8
$lines | Where-Object { $_ -match 'syncSize|onPaint|装载|chatHost|准备|showApp|StageResizer|输入转发|窗口|window|setSize|OSR' } | Select-Object -Last 80
