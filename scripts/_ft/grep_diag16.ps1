$ErrorActionPreference = 'Continue'
$log = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
$lines = Get-Content $log -Encoding UTF8
$s = -1; $e = -1
for ($i = 0; $i -lt $lines.Count; $i++) { if ($lines[$i] -match '^2026-10-09 21:41:22') { $s = $i; break } }
for ($i = $s; $i -lt $lines.Count; $i++) { if ($lines[$i] -match '^2026-10-09 21:41:23') { $e = $i; break } }
Write-Host ("RANGE [$s..$e]")
if ($s -ge 0 -and $e -gt $s) { $lines[$s..($e-1)] | ForEach-Object { $_ } }
