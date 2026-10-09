$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$f = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
# Get-Content uses shared read; ReadAllLines fails while the app holds the file locked.
$lines = @(Get-Content -LiteralPath $f -Encoding UTF8)
# Chinese keywords constructed from code points to keep this script pure ASCII
# 0x5408 0x6210 0x62D6 0x52A8 0x515C 0x5E95 = "hecheng tuodong doudi" (fallback)
$kwFallback = -join (@(0x5408, 0x6210, 0x62D6, 0x52A8, 0x515C, 0x5E95) | ForEach-Object { [char]$_ })
# 0x8F93 0x5165 0x8F6C 0x53D1 = "shuru zhuanfa" (input forwarding)
$kwForward = -join (@(0x8F93, 0x5165, 0x8F6C, 0x53D1) | ForEach-Object { [char]$_ })

$fb = @($lines | Where-Object { $_ -match $kwFallback })
$fw = @($lines | Where-Object { $_ -match $kwForward })
"TOTAL=$($lines.Count) FALLBACK=$($fb.Count) FORWARD=$($fw.Count)"
"==== last 60 fallback ===="
$fb | Select-Object -Last 60
"==== last 30 forward ===="
$fw | Select-Object -Last 30
