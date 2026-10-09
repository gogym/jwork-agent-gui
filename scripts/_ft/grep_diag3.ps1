$ErrorActionPreference = 'Continue'
$log = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
Write-Host ('LOG MTIME: ' + (Get-Item $log).LastWriteTime)
$lines = Get-Content $log -Tail 4000 -Encoding UTF8
$hits = $lines | Where-Object { $_ -match '观测|输入转发|DRAG|MOVE|PRESS' }
if ($hits) {
  Write-Host ('MATCH COUNT: ' + $hits.Count)
  $hits | Select-Object -Last 100
} else {
  Write-Host 'NO MATCH - raw tail 60'
  $lines | Select-Object -Last 60
}
