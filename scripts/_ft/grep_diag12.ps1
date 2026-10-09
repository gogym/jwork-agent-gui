$ErrorActionPreference = 'Continue'
$log = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
# 全文件按时间戳抓 21:40:00 以后的关键事件行（PRESS/RELEASE/DRAG/realize/装载/syncSize）
$hits = Select-String -Path $log -Pattern '^2026-10-09 21:4[0-9]:.*(输入转发 (PRESS|RELEASE|DRAG)|观测 (PRESS|DRAG|RELEASE)|JCEF (realize|主框架装载|native)|syncSize 首次)' -Encoding UTF8
Write-Host ('TOTAL HITS since 21:40: ' + $hits.Count)
$hits | ForEach-Object { $_.Line } | Select-Object -First 250
