$ErrorActionPreference = "SilentlyContinue"
$roots = @(
    (Join-Path $env:USERPROFILE ".m2\repository\io\agentscope"),
    "D:\Program Files\maven-mvnd-1.0.6-windows-amd64\m2\io\agentscope"
)
foreach ($r in $roots) {
    Write-Output "=== $r ==="
    if (Test-Path $r) {
        Get-ChildItem -Path $r -Recurse -Filter *.jar | ForEach-Object { $_.FullName }
    } else {
        Write-Output "(not found)"
    }
}
