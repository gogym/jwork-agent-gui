# 输出当前所有 java 进程的完整命令行，定位 -javaagent 来源（IDE 覆盖率 agent 等）
$out = 'D:\GitHub\gim-agent-gui\scripts\_ft\java_procs.txt'
Remove-Item $out -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | ForEach-Object {
    "PID=$($_.ProcessId)"
    "CMD=$($_.CommandLine)"
    "-----"
} | Out-File -FilePath $out -Encoding UTF8
Write-Output "DONE"
