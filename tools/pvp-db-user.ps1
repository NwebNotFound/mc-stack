$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'db-user-common.ps1')
Invoke-DbUserSetup 'CRAFTCORE_PVP_DB_PASSWORD' 'scripts/pvp-db-user.sql' '@PVP_DB_PASSWORD@'
Write-Host 'craft_pvp: Tabellen und Grants ok.'
