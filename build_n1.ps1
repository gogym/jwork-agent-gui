$env:JAVA_HOME = 'D:\Program Files\BellSoft\jdk-21.0.8'
Set-Location D:\GitHub\gim-agent-gui
mvn -q -pl gim-agent-core -am test "-Dtest=EmbeddingIndexServiceTest,KnowledgeServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
mvn -q -pl gim-agent-ui -am -DskipTests compile
