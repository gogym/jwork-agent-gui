$env:JAVA_HOME = 'D:\Program Files\BellSoft\jdk-21.0.8'
$env:Path = $env:JAVA_HOME + '\bin;' + $env:Path
Set-Location 'D:\GitHub\gim-agent-gui'
mvn -q dependency:get "-Dartifact=me.friwi:jcefmaven:141.0.10"
Write-Host ("EXITCODE=" + $LASTEXITCODE)
