$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'db-user-common.ps1')
Invoke-DbUserSetup 'CRAFTCORE_VIP_DB_PASSWORD' 'scripts/vip-db-user.sql' '@VIP_DB_PASSWORD@'
Write-Host 'craft_vip: Grants ok.'
