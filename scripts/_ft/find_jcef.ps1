# 探查本机 Maven 仓库里的 jcef/jcefmaven 构件（供分析 java-cef 的 OSR onPaint 实现）
$ErrorActionPreference = 'SilentlyContinue'
$repo = 'D:\Program Files\maven-mvnd-1.0.6-windows-amd64\m2'
Write-Output "== repo exists: $(Test-Path $repo)"
$dirs = Get-ChildItem -Path (Join-Path $repo 'me\friwi') -Directory
foreach ($d in $dirs) { Write-Output ("DIR: " + $d.Name) }
$jars = Get-ChildItem -Path (Join-Path $repo 'me\friwi') -Recurse -Filter '*.jar'
foreach ($j in $jars) { Write-Output ("JAR: " + $j.FullName) }
