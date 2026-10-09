Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class JworkWin32 {
    [DllImport("user32.dll")]
    public static extern short GetAsyncKeyState(int vKey);
}
"@
$p = [System.Windows.Forms.Cursor]::Position
$down = ([JworkWin32]::GetAsyncKeyState(0x01) -band 0x8000) -ne 0
Write-Host ("OK pos=" + $p.X + "," + $p.Y + " L=" + $down)
