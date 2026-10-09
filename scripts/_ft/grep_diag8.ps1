$ErrorActionPreference = 'Continue'
$log = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
Write-Host ('LOG MTIME: ' + (Get-Item $log).LastWriteTime)
$lines = Get-Content $log -Tail 8000 -Encoding UTF8
$hits = $lines | Where-Object { $_ -match '观测|输入转发|syncSize' }
Write-Host ('MATCH COUNT: ' + $hits.Count)
Write-Host '=== last 180 observation hits ==='
$hits | Select-Object -Last 180
Write-Host ''
Write-Host '=== cursor_trace.log last 80 lines ==='
$ct = 'D:\GitHub\gim-agent-gui\build\cursor_trace.log'
if (Test-Path $ct) { Get-Content $ct -Tail 80 -Encoding UTF8 } else { Write-Host 'MISSING' }
