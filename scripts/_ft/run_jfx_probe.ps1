$env:JAVA_HOME = 'D:\Program Files\BellSoft\jdk-21.0.8'
Set-Location 'D:\GitHub\gim-agent-gui'
$ErrorActionPreference = 'Continue'

$cpFile = 'D:\GitHub\gim-agent-gui\gim-agent-ui\cp_probe.txt'
$deps = (Get-Content $cpFile -Raw).Trim()
$cp = 'D:\GitHub\gim-agent-gui\gim-agent-ui\target\classes;' `
    + 'D:\GitHub\gim-agent-gui\gim-agent-core\target\classes;' `
    + 'D:\GitHub\gim-agent-gui\gim-agent-domain\target\classes;' `
    + 'D:\GitHub\gim-agent-gui\gim-agent-tools\target\classes;' `
    + 'D:\GitHub\gim-agent-gui\gim-agent-scheduler\target\classes;' `
    + $deps

$java = 'D:\Program Files\BellSoft\jdk-21.0.8\bin\java.exe'
& $java --add-exports=java.base/java.lang=ALL-UNNAMED --add-exports=java.desktop/sun.awt=ALL-UNNAMED --add-exports=java.desktop/sun.java2d=ALL-UNNAMED --add-opens=java.desktop/sun.awt=ALL-UNNAMED -cp $cp 'D:\GitHub\gim-agent-gui\scripts\_ft\JfxChainProbe.java' 2>&1 | Tee-Object -FilePath 'D:\GitHub\gim-agent-gui\build\jfx_probe.log'
Write-Host ('JAVA_EXIT=' + $LASTEXITCODE)
