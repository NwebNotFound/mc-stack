# Legt craft_pvp an bzw. setzt Passwort/Grants (scripts/pvp-db-user.sql) als craft_admin.
# Erzeugt CRAFTCORE_PVP_DB_PASSWORD in .env, falls es fehlt. Gibt keine Secrets aus.
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')
$envFile = Join-Path $root '.env'
$vals = @{}
foreach ($l in Get-Content $envFile) { if ($l -match '^([A-Z0-9_]+)=(.*)$') { $vals[$Matches[1]] = $Matches[2].Trim() } }
if (-not $vals['CRAFTCORE_PVP_DB_PASSWORD']) {
  $b = New-Object byte[] 24; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
  $vals['CRAFTCORE_PVP_DB_PASSWORD'] = -join ($b | ForEach-Object { $_.ToString('x2') })
  Add-Content $envFile "CRAFTCORE_PVP_DB_PASSWORD=$($vals['CRAFTCORE_PVP_DB_PASSWORD'])"
  Write-Host 'CRAFTCORE_PVP_DB_PASSWORD in .env erzeugt.'
}
foreach ($k in 'MARIADB_ADMIN_USER','MARIADB_ADMIN_PASSWORD') { if (-not $vals[$k]) { throw "$k fehlt in .env" } }
$sql = (Get-Content (Join-Path $root 'scripts\pvp-db-user.sql') -Raw).Replace('@PVP_DB_PASSWORD@', $vals['CRAFTCORE_PVP_DB_PASSWORD'])
$env:MC_ADM_U = $vals['MARIADB_ADMIN_USER']; $env:MC_ADM_P = $vals['MARIADB_ADMIN_PASSWORD']
try {
  Set-Location $root
  $sql | docker compose exec -T -e MC_ADM_U -e MC_ADM_P craftcore-db sh -c 'exec mariadb -u"$MC_ADM_U" -p"$MC_ADM_P" craftcore'
  if ($LASTEXITCODE -ne 0) { throw 'SQL fehlgeschlagen' }
  Write-Host 'craft_pvp: Tabellen + Grants ok.'
} finally { Remove-Item Env:MC_ADM_U, Env:MC_ADM_P -ErrorAction SilentlyContinue }