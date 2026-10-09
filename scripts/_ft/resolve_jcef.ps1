Set-Location 'D:\GitHub\gim-agent-gui'
$ErrorActionPreference = 'Continue'
& mvn -q -pl gim-agent-ui -am -DskipTests dependency:tree "-Dincludes=me.friwi:*"
Write-Host ('MVN_EXIT=' + $LASTEXITCODE)
