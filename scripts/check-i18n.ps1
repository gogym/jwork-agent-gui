$ErrorActionPreference = 'Stop'
$root = 'D:\GitHub\gim-agent-gui'
$i18nDir = Join-Path $root 'gim-agent-ui\src\main\resources\i18n'
$files = Get-ChildItem -Path $i18nDir -Filter 'messages*.properties' | Sort-Object Name

function Get-Keys($path) {
  $keys = @()
  foreach ($line in Get-Content -LiteralPath $path -Encoding UTF8) {
    if ($line -match '^\s*([A-Za-z0-9_.]+)\s*=') { $keys += $Matches[1] }
  }
  return $keys
}

$sets = @{}
foreach ($f in $files) {
  $k = Get-Keys $f.FullName
  $sets[$f.Name] = $k
  Write-Output ("{0} -> {1} keys" -f $f.Name, $k.Count)
}

$refName = ($sets.Keys | Sort-Object)[0]
$ref = $sets[$refName]
foreach ($name in ($sets.Keys | Sort-Object)) {
  $diff = Compare-Object -ReferenceObject $ref -DifferenceObject $sets[$name]
  Write-Output ("diff {0} vs {1} = {2}" -f $name, $refName, (@($diff).Count))
  if (@($diff).Count -gt 0) { $diff | ForEach-Object { Write-Output ("   " + $_.SideIndicator + " " + $_.InputObject) } }
}

$q = [char]34
$pattern = "I18n\.t\(\s*" + $q + "([A-Za-z0-9_.]+)" + $q
$javaFiles = Get-ChildItem -Path (Join-Path $root 'gim-agent-ui\src') -Recurse -Filter '*.java'
$used = @()
foreach ($j in $javaFiles) {
  foreach ($line in Get-Content -LiteralPath $j.FullName -Encoding UTF8) {
    foreach ($m in [regex]::Matches($line, $pattern)) { $used += $m.Groups[1].Value }
  }
}
$used = $used | Sort-Object -Unique
Write-Output ("used keys = {0}" -f @($used).Count)
$missing = @($used | Where-Object { $ref -notcontains $_ })
Write-Output ("missing = {0}" -f $missing.Count)
$missing | ForEach-Object { Write-Output ("   MISSING " + $_) }

$new = @('section.sessions','sidebar.search.prompt','sidebar.agent.new','sidebar.agent.configure',
         'session.menu.agent','session.agentTip','session.rebindConfirm',
         'settings.agent.skills','settings.agent.mcp','settings.agent.none',
         'agent.skills','agent.mcp','agent.cap.hint','agent.cap.noneSkills','agent.cap.noneMcp','agent.cap.disabled')
foreach ($name in ($sets.Keys | Sort-Object)) {
  $absent = @($new | Where-Object { $sets[$name] -notcontains $_ })
  Write-Output ("{0} new-key gaps {1}" -f $name, $absent.Count)
  $absent | ForEach-Object { Write-Output ("   " + $_) }
}

# keys retired with the workspace grouping feature
$banned = @('workspace.menu.agent.configure','workspace.new.title','workspace.ungrouped','session.moveTo',
            'section.workspace','section.recent')
foreach ($name in ($sets.Keys | Sort-Object)) {
  $left = @($banned | Where-Object { $sets[$name] -contains $_ })
  Write-Output ("{0} retired-key leftovers {1}" -f $name, $left.Count)
  $left | ForEach-Object { Write-Output ("   LEFTOVER " + $_) }
}
