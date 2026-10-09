$ErrorActionPreference = 'Continue'
$log = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
$lines = Get-Content $log -Tail 30000 -Encoding UTF8
Write-Host ("TAIL LINES: " + $lines.Count)
Write-Host '=== all canvas-level PRESS/RELEASE (exact events) ==='
$ev = $lines | Where-Object { $_ -match '输入转发 (PRESS|RELEASE):' }
Write-Host ('EVENT COUNT: ' + $ev.Count)
$ev | Select-Object -Last 40
Write-Host ''
Write-Host '=== all canvas-level DRAG hits (throttled + synthesized) ==='
$dr = $lines | Where-Object { $_ -match '输入转发 DRAG' }
Write-Host ('DRAG COUNT: ' + $dr.Count)
$dr | Select-Object -Last 40
Write-Host ''
Write-Host '=== newest 800 lines first timestamp check ==='
$first = $lines | Select-Object -First 1
$last = $lines | Select-Object -Last 1
Write-Host ('FIRST: ' + $first.Substring(0, [Math]::Min(23, $first.Length)))
Write-Host ('LAST: ' + $last.Substring(0, [Math]::Min(23, $last.Length)))
