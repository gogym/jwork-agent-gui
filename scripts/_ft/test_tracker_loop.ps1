# tracker 排查：前台全写模式跑 12 秒——验证循环是否在跑、Cursor.Position 是否可读
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class JworkWin32b {
    [DllImport("user32.dll")]
    public static extern short GetAsyncKeyState(int vKey);
}
"@
$n = 0
$t0 = Get-Date
while (((Get-Date) - $t0).TotalSeconds -lt 12) {
    try {
        $p = [System.Windows.Forms.Cursor]::Position
        $down = ([JworkWin32b]::GetAsyncKeyState(0x01) -band 0x8000) -ne 0
        $n++
        Write-Host ("tick=$n pos=" + $p.X + "," + $p.Y + " L=" + $down)
    } catch {
        Write-Host ("TICK ERROR: " + $_.Exception.Message)
    }
    Start-Sleep -Milliseconds 500
}
Write-Host "DONE ticks=$n"
