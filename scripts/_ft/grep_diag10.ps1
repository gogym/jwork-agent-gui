$ErrorActionPreference = 'Continue'
$log = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
$lines = Get-Content $log -Tail 12000 -Encoding UTF8
$start = -1; $end = -1
for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match '^2026-10-09 21:39:5') { $start = $i; break } }
for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match '^2026-10-09 21:42:36') { $end = $i; break } }
Write-Host ("RANGE start=$start end=$end")
if ($start -ge 0 -and $end -gt $start) {
  $seg = $lines[$start..($end-1)]
  Write-Host ("SEG TOTAL: " + $seg.Count)
  $seg | Where-Object { $_ -match '观测 |输入转发|realize|装载|syncSize|onPaint|ERROR|WARN|JS console' } | Select-Object -First 240
  Write-Host '--- blank-line-joined view: press/release/drag only ---'
  $seg | Where-Object { $_ -match '输入转发 (PRESS|RELEASE|DRAG)|观测 (PRESS|DRAG|RELEASE)' } | Select-Object -First 120
} else { Write-Host 'range not found' }
