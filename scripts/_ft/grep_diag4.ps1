$ErrorActionPreference = 'Continue'
$log = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
$lines = Get-Content $log -Tail 4000 -Encoding UTF8
Write-Host '=== lines between 21:0 and 21:20 (startup markers + stack traces) ==='
$idx = -1
for ($i = 0; $i -lt $lines.Count; $i++) {
  if ($lines[$i] -match '21:1[0-9]:|21:0[89]:|Exception|at com\.|at java\.|Caused') { $idx = $i }
}
# print a window around interesting areas: all lines containing startup markers or stack frames
$lines | Where-Object { $_ -match 'CefApp 就绪|页面已落地|realize 成功|syncSize|onPaint 首次|showApp|Exception|^\s+at |Caused by' } | Select-Object -Last 60
