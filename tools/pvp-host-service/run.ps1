# Startet den PvP-Host-Dienst mit PVP_HOST_* und VIP_HOST_TOKEN aus ..\..\.env (Token wird nie ausgegeben).
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
foreach ($l in Get-Content (Join-Path $root '.env')) {
  if ($l -match '^(PVP_HOST_[A-Z_]+|VIP_HOST_TOKEN)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process') }
}
Set-Location $root
& 'C:\Program Files\Python311\python.exe' (Join-Path $PSScriptRoot 'service.py')
