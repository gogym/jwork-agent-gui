# 诊断 Start-Process 启动追踪器失败的原因
$err = 'D:\GitHub\gim-agent-gui\build\trace_err.txt'
$outf = 'D:\GitHub\gim-agent-gui\build\trace_out.txt'
$p = Start-Process powershell -WindowStyle Hidden -PassThru -RedirectStandardError $err -RedirectStandardOutput $outf -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', 'D:\GitHub\gim-agent-gui\scripts\_ft\cursor_trace.ps1')
Start-Sleep -Seconds 6
Write-Host ('STARTED PID=' + $p.Id + ' HasExited=' + $p.HasExited)
if ($p.HasExited) { Write-Host ('ExitCode=' + $p.ExitCode) }
if (Test-Path $err) { Write-Host '--- stderr ---'; Get-Content $err -Tail 20 }
if (Test-Path $outf) { Write-Host '--- stdout ---'; Get-Content $outf -Tail 20 }
$log = 'D:\GitHub\gim-agent-gui\build\cursor_trace.log'
if (Test-Path $log) { Write-Host '--- cursor_trace.log ---'; Get-Content $log -Tail 6 } else { Write-Host '--- cursor_trace.log STILL MISSING ---' }
