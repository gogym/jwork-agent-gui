$ErrorActionPreference = 'Continue'
$f = 'C:\Users\Administrator\.jwork\logs\gim-agent.log'
try {
    $lines = [System.IO.File]::ReadAllLines($f, [System.Text.Encoding]::UTF8)
    "RAL OK count=$($lines.Count)"
    "first line: $($lines[0])"
} catch {
    "RAL ERR: $($_.Exception.GetType().Name): $($_.Exception.Message)"
}
try {
    $c = Get-Content -LiteralPath $f -Encoding UTF8 -TotalCount 3
    "GC OK count=$($c.Count)"
    "gc first: $($c[0])"
} catch {
    "GC ERR: $($_.Exception.GetType().Name): $($_.Exception.Message)"
}
