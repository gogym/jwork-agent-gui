$env:JAVA_HOME = 'D:\Program Files\BellSoft\jdk-21.0.8'
Set-Location D:\GitHub\gim-agent-gui
mvn -q -pl gim-agent-tools -am test "-Dtest=SandboxExecutorTest,SandboxProvisionerTest" "-Dsurefire.failIfNoSpecifiedTests=false"
