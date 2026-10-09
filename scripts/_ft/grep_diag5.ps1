$ErrorActionPreference = 'Continue'
$log = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
Write-Host ('LOG MTIME: ' + (Get-Item $log).LastWriteTime)
Write-Host '=== 本轮用户操作段：观测 MOVE/DRAG/PRESS/RELEASE + 输入转发 (last 250 hits) ==='
$lines = Get-Content $log -Tail 6000 -Encoding UTF8
$hits = $lines | Where-Object { $_ -match '观测|输入转发|syncSize 首次' }
Write-Host ('MATCH COUNT: ' + $hits.Count)
$hits | Select-Object -Last 150
