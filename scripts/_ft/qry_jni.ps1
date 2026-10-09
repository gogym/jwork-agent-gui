$f = 'C:\Users\Administrator\.qoder-cn\cache\projects\gim-agent-gui-defc49be\agent-tools\5c1340df\99c20d09.txt'
Select-String -Path $f -Pattern 'CefModifiers' | ForEach-Object {
  Write-Host ($_.LineNumber.ToString() + ': ' + $_.Line.Trim())
}
Write-Host '---DONE---'
