$ErrorActionPreference = 'Continue'
Write-Host '--- JAVA PROCESSES ---'
Get-Process -Name java -ErrorAction SilentlyContinue | Select-Object Id,StartTime,@{N='Cmd';E={$_.MainWindowTitle}} | Format-Table -AutoSize
Write-Host '--- LOG TAIL (last 60 lines) ---'
$log = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
if (Test-Path $log) {
  Get-Content $log -Tail 60 -Encoding UTF8
} else {
  Write-Host 'NO LOG FILE'
}
Write-Host '--- LOG FILE MTIME ---'
(Get-Item $log -ErrorAction SilentlyContinue).LastWriteTime
