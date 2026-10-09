$ErrorActionPreference = 'Continue'
$log = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
$lines = Get-Content $log -Tail 6000 -Encoding UTF8
# 找到 21:32:00 ~ 21:33:52 的段（用户拖滚动条 + 悬停段），打印原始行
$start = -1
for ($i = 0; $i -lt $lines.Count; $i++) {
  if ($lines[$i] -match '^2026-10-09 21:32:') { $start = $i; break }
}
$end = -1
for ($i = $lines.Count - 1; $i -ge 0; $i--) {
  if ($lines[$i] -match '^2026-10-09 21:33:55') { $end = $i; break }
}
Write-Host ("RANGE start=$start end=$end total=$($lines.Count)")
if ($start -ge 0 -and $end -gt $start) {
  $seg = $lines[$start..($end-1)]
  Write-Host ("SEG LINES: " + $seg.Count)
  $seg | Where-Object { $_ -match '观测|输入转发|syncSize|onPaint|Exception' } | Select-Object -First 200
} else {
  Write-Host 'range not found'
}
