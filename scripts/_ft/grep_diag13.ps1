$ErrorActionPreference = 'Continue'
$log = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
$lines = Get-Content $log -Encoding UTF8
Write-Host ('TOTAL LINES: ' + $lines.Count)
# 找 21:40:00 起点的下标
$idx = -1
for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match '^2026-10-09 21:40:') { $idx = $i; break } }
Write-Host ('First 21:40 line index: ' + $idx)
if ($idx -ge 0) {
  $seg = $lines[$idx..($lines.Count - 1)]
  Write-Host ('SEG FROM 21:40: ' + $seg.Count + ' lines')
  $key = $seg | Where-Object { $_ -match '输入转发 PRESS|输入转发 RELEASE|输入转发 DRAG|观测 PRESS|观测 DRAG|观测 RELEASE|realize 成功|主框架装载完成|syncSize 首次' }
  Write-Host ('KEY EVENT LINES: ' + $key.Count)
  $key | Select-Object -First 260
}
