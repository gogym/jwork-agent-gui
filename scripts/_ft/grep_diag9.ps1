$ErrorActionPreference = 'Continue'
$log = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
Write-Host ('LOG MTIME: ' + (Get-Item $log).LastWriteTime)
Write-Host '=== gim-agent.log last 130 raw lines ==='
Get-Content $log -Tail 130 -Encoding UTF8
Write-Host ''
Write-Host '=== trace process check ==='
Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object { $_.CommandLine -match 'cursor_trace' } | Select-Object ProcessId | Format-List
Write-Host '=== trace2 err/out ==='
foreach ($f in @('D:\GitHub\gim-agent-gui\build\trace2_err.txt','D:\GitHub\gim-agent-gui\build\trace2_out.txt')) {
  if (Test-Path $f) { Write-Host ('-- ' + $f); Get-Content $f -Tail 10 }
}
