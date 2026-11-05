$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'db-user-common.ps1')
Invoke-DbUserSetup -SqlFile 'scripts/creepy-ro-grants.sql'
Write-Host 'creepy_ro: acht SELECT-Grants angewendet.'
