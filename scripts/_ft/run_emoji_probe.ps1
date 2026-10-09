$env:JAVA_HOME = 'D:\Program Files\BellSoft\jdk-21.0.8'
Set-Location 'D:\GitHub\gim-agent-gui'
$ErrorActionPreference = 'Continue'

& mvn -q -pl gim-agent-ui -am dependency:build-classpath "-Dmdep.outputFile=cp_probe.txt" 2>&1 | Tee-Object -FilePath 'D:\GitHub\gim-agent-gui\build\probe_cp.log' | Out-Null
Write-Host ('MVN_CP_EXIT=' + $LASTEXITCODE)

$cpFile = 'D:\GitHub\gim-agent-gui\gim-agent-ui\cp_probe.txt'
if (-not (Test-Path $cpFile)) { Write-Host 'NO_CP_FILE'; exit 1 }
$deps = (Get-Content $cpFile -Raw).Trim()
$cp = 'D:\GitHub\gim-agent-gui\gim-agent-ui\target\classes;' `
    + 'D:\GitHub\gim-agent-gui\gim-agent-core\target\classes;' `
    + 'D:\GitHub\gim-agent-gui\gim-agent-domain\target\classes;' `
    + 'D:\GitHub\gim-agent-gui\gim-agent-tools\target\classes;' `
    + 'D:\GitHub\gim-agent-gui\gim-agent-scheduler\target\classes;' `
    + $deps

$java = 'D:\Program Files\BellSoft\jdk-21.0.8\bin\java.exe'
& $java --add-exports=java.base/java.lang=ALL-UNNAMED --add-exports=java.desktop/sun.awt=ALL-UNNAMED --add-exports=java.desktop/sun.java2d=ALL-UNNAMED --add-opens=java.desktop/sun.awt=ALL-UNNAMED -cp $cp 'D:\GitHub\gim-agent-gui\scripts\_ft\EmojiProbe.java' 2>&1 | Tee-Object -FilePath 'D:\GitHub\gim-agent-gui\build\probe_run.log'
Write-Host ('JAVA_EXIT=' + $LASTEXITCODE)
