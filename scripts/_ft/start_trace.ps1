# 启动光标追踪器 v2 为独立后台进程（带输出重定向，Hidden 模式稳定启动），并自检
$err = 'D:\GitHub\gim-agent-gui\build\trace2_err.txt'
$outf = 'D:\GitHub\gim-agent-gui\build\trace2_out.txt'
$p = Start-Process powershell -WindowStyle Hidden -PassThru -RedirectStandardError $err -RedirectStandardOutput $outf -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', 'D:\GitHub\gim-agent-gui\scripts\_ft\cursor_trace.ps1')
Start-Sleep -Seconds 6
Write-Host ('PID=' + $p.Id + ' HasExited=' + $p.HasExited)
$f = 'D:\GitHub\gim-agent-gui\build\cursor_trace.log'
if (Test-Path $f) { Write-Host '--- cursor_trace.log head/tail ---'; Get-Content $f -TotalCount 3; Write-Host '...'; Get-Content $f -Tail 3 }
if (Test-Path $err) { $e = Get-Content $err -Raw; if ($e) { Write-Host '--- stderr ---'; Write-Host $e } }
