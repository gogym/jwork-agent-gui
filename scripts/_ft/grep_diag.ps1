$ErrorActionPreference = 'Continue'
$log = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
Write-Host ('LOG MTIME: ' + (Get-Item $log).LastWriteTime)
Write-Host '--- StageResizer / 输入转发 lines (last 2000 lines scan) ---'
$lines = Get-Content $log -Tail 2000 -Encoding UTF8
$hits = $lines | Where-Object { $_ -match 'StageResizer|输入转发|骞|杈撳' }
if ($hits) {
  $hits | Select-Object -Last 120
} else {
  Write-Host 'NO MATCH - dumping raw tail 80'
  $lines | Select-Object -Last 80
}
