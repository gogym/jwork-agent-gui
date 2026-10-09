$ErrorActionPreference = 'SilentlyContinue'
$path = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
Write-Output '==== LOG TAIL 240 (raw UTF8) ===='
Get-Content -Path $path -Encoding UTF8 -Tail 240
Write-Output '==== WEB DIR ===='
$dir = Join-Path $env:TEMP 'jwork\cef-web\chat'
Write-Output ('DIR=' + $dir)
Get-ChildItem $dir | ForEach-Object { Write-Output ($_.Name + '  ' + $_.Length + '  ' + $_.LastWriteTime) }
Write-Output '----index.html first 30 lines----'
Get-Content -Path (Join-Path $dir 'index.html') -Encoding UTF8 -TotalCount 30
Write-Output '----index.html GIM_BOOT / script src hits----'
Select-String -Path (Join-Path $dir 'index.html') -Pattern 'GIM_BOOT' | Select-Object -First 3
Select-String -Path (Join-Path $dir 'index.html') -Pattern 'script src' | Select-Object -First 5
Write-Output '==== CLASS TIMESTAMPS (target/classes/jcef) ===='
$cls = 'D:\GitHub\gim-agent-gui\gim-agent-ui\target\classes\com\gettyio\gim\agent\ui\jcef'
Get-ChildItem $cls | ForEach-Object { Write-Output ($_.Name + '  ' + $_.LastWriteTime) }
Write-Output '==== SRC TIMESTAMPS ===='
Get-ChildItem 'D:\GitHub\gim-agent-gui\gim-agent-ui\src\main\java\com\gettyio\gim\agent\ui\jcef' | ForEach-Object { Write-Output ($_.Name + '  ' + $_.LastWriteTime) }
Write-Output '==== END ===='
