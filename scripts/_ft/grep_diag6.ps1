$ErrorActionPreference = 'Continue'
$dir = 'C:\Users\Administrator\.jwork\logs'
Write-Host '=== log dir files ==='
Get-ChildItem $dir | Select-Object Name, Length, LastWriteTime | Format-Table -AutoSize

Write-Host '=== gim-agent.log last 60 raw lines ==='
Get-Content (Join-Path $dir 'gim-agent.log') -Tail 60 -Encoding UTF8

Write-Host '=== cursor_trace.log last 60 lines ==='
$ct = 'D:\GitHub\gim-agent-gui\build\cursor_trace.log'
if (Test-Path $ct) { Get-Content $ct -Tail 60 -Encoding UTF8 } else { Write-Host 'MISSING' }
