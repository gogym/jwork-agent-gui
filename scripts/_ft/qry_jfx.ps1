$f = 'D:\GitHub\gim-agent-gui\scripts\_ft\GlassViewEventHandler.java.txt'
Select-String -Path $f -Pattern 'primaryButtonDown|secondaryButtonDown|getButtonMask|BUTTON_LEFT' | ForEach-Object {
  Write-Host ($_.LineNumber.ToString() + ': ' + $_.Line.Trim())
}
Write-Host '---DONE---'
