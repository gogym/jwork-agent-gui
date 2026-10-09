$ErrorActionPreference = 'Continue'
$log = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
$lines = Get-Content $log -Encoding UTF8
Write-Host ('TOTAL LINES: ' + $lines.Count)
Write-Host '=== lines around index 44561 (the so-called first 21:40 line) ==='
$lines[44555..44570] | ForEach-Object { $_ }
Write-Host ''
Write-Host '=== search exact "syncSize 首次" occurrences with index ==='
for ($i = 0; $i -lt $lines.Count; $i++) {
  if ($lines[$i] -match 'syncSize 首次') { Write-Host ("[$i] " + $lines[$i]) }
}
Write-Host ''
Write-Host '=== search "观测 PRESS" occurrences with index (last 12) ==='
$cnt = 0
for ($i = 0; $i -lt $lines.Count; $i++) {
  if ($lines[$i] -match '观测 PRESS') { $cnt++ }
}
Write-Host ('观测 PRESS total: ' + $cnt)
$shown = 0
for ($i = $lines.Count - 1; $i -ge 0 -and $shown -lt 12; $i--) {
  if ($lines[$i] -match '观测 PRESS') { Write-Host ("[$i] " + $lines[$i]); $shown++ }
}
