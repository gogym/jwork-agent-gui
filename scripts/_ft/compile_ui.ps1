$env:JAVA_HOME = 'D:\Program Files\BellSoft\jdk-21.0.8'
Set-Location 'D:\GitHub\gim-agent-gui'
$ErrorActionPreference = 'Continue'
& mvn -q -pl gim-agent-ui -am -DskipTests compile 2>&1 | Tee-Object -FilePath 'D:\GitHub\gim-agent-gui\build\jcef_compile.log'
Write-Host ('MVN_EXIT=' + $LASTEXITCODE)
