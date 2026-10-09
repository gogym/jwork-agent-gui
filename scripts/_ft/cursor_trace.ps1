# 光标+左键状态追踪器 v2（RDP 拖动问题诊断专用，诊断结束后删除）
# v2 改进：包裹异常保护（v1 因偶发异常静默退出）+ 30 秒心跳行（验证进程存活与位置可读）
$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class JworkWin32c {
    [DllImport("user32.dll")]
    public static extern short GetAsyncKeyState(int vKey);
}
"@
$out = 'D:\GitHub\gim-agent-gui\build\cursor_trace.log'
$deadline = (Get-Date).AddMinutes(45)
Set-Content -Path $out -Value ("# trace2 start " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff'))
$lastKey = ''
$lastHeartbeat = Get-Date
while ((Get-Date) -lt $deadline) {
    try {
        $p = [System.Windows.Forms.Cursor]::Position
        $down = ([JworkWin32c]::GetAsyncKeyState(0x01) -band 0x8000) -ne 0
        $key = "$($p.X),$($p.Y),$down"
        if ($key -ne $lastKey) {
            $lastKey = $key
            Add-Content -Path $out -Value ((Get-Date -Format 'HH:mm:ss.fff') + ' pos=' + $p.X + ',' + $p.Y + ' L=' + $down)
        }
        if (((Get-Date) - $lastHeartbeat).TotalSeconds -ge 30) {
            $lastHeartbeat = Get-Date
            Add-Content -Path $out -Value ('# heartbeat ' + (Get-Date -Format 'HH:mm:ss.fff') + ' pos=' + $p.X + ',' + $p.Y + ' L=' + $down)
        }
    } catch {
        Add-Content -Path $out -Value ('# TICK ERROR ' + (Get-Date -Format 'HH:mm:ss.fff') + ': ' + $_.Exception.Message)
    }
    Start-Sleep -Milliseconds 80
}
Add-Content -Path $out -Value ("# trace2 end " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff'))
