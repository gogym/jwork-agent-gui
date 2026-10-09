$ErrorActionPreference = 'Continue'
$log = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
$lines = Get-Content $log -Encoding UTF8
$seg = $lines[44561..($lines.Count - 1)]
Write-Host ('SEG LINES: ' + $seg.Count)
Write-Host '=== rare lines (all except high-freq hover MOVE noise) ==='
$rare = $seg | Where-Object { ($_ -notmatch 'MOVE') -or ($_ -match 'DRAG') -or ($_ -match 'PRESS') -or ($_ -match 'RELEASE') }
Write-Host ('RARE COUNT: ' + $rare.Count)
$rare | Select-Object -First 220
