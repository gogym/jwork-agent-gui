$ErrorActionPreference = 'Stop'
$outDir = 'D:\GitHub\gim-agent-gui\scripts\_ft\jfxsrc'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Set-Location $outDir
$jar = 'D:\Program Files\maven-mvnd-1.0.6-windows-amd64\m2\org\openjfx\javafx-graphics\21.0.8\javafx-graphics-21.0.8-sources.jar'
& 'D:\Program Files\BellSoft\jdk-21.0.8\bin\jar.exe' xf $jar javafx/scene/Scene.java
$f = Join-Path $outDir 'javafx\scene\Scene.java'
Write-Host ('OK: ' + $f + ' ' + (Get-Item $f).Length + ' bytes')
