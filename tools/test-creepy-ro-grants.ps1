# No Docker/DB access: exercise the real helper with fixture credentials and a mock.
$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ('creepy-grants-test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path "$root/tools", "$root/scripts" | Out-Null
Copy-Item "$PSScriptRoot/db-user-common.ps1", "$PSScriptRoot/creepy-ro-grants.ps1" "$root/tools"
Copy-Item "$PSScriptRoot/../scripts/creepy-ro-grants.sql" "$root/scripts"
$global:CreepyGrantTestCalls = New-Object System.Collections.Generic.List[object]
$global:CreepyGrantTestFailSql = $false
function docker {
  begin { $arguments = @($args); $lines = @() }
  process { $lines += [string]$_ }
  end {
    $global:CreepyGrantTestCalls.Add(@{ Arguments = $arguments; InputText = ($lines -join "`n") })
    $global:LASTEXITCODE = 0
    if ($global:CreepyGrantTestFailSql -and $arguments -contains 'mariadb') { $global:LASTEXITCODE = 1 }
  }
}
function Assert($condition, $message) { if (-not $condition) { throw $message } }
function Set-FixtureEnv {
  @('MARIADB_ADMIN_USER=fixture_admin', 'MARIADB_ADMIN_PASSWORD=fixture_admin_value', "FIXTURE_PASSWORD='fixture_target_value'") |
    Set-Content "$root/.env" -Encoding UTF8
}
Set-FixtureEnv
$sql = Get-Content "$root/scripts/creepy-ro-grants.sql" -Raw
$grants = @($sql -split "`n" | Where-Object { $_ -match '^GRANT ' })
Assert ($grants.Count -eq 8) 'Expected exactly eight grants'
foreach ($grant in $grants) {
  Assert ($grant.Trim() -match "^GRANT SELECT ON craftcore\.[a-z_]+ TO 'creepy_ro'@'%';$") 'Unexpected privilege or recipient'
}
Assert ($sql.IndexOf("'NO_AUTO_CREATE_USER'") -lt $sql.IndexOf('GRANT SELECT')) 'Missing implicit-user creation guard'
Assert ($sql -notmatch '(?im)^\s*(CREATE|ALTER|DROP|DELETE|INSERT|UPDATE|REVOKE|FLUSH)\b') 'Unexpected SQL mutation'
& "$root/tools/creepy-ro-grants.ps1"
Assert ($global:CreepyGrantTestCalls.Count -eq 3) 'Expected option file, SQL and cleanup only'
Assert ($global:CreepyGrantTestCalls[1].InputText.Trim() -eq $sql.Trim()) 'Admin SQL must be passed unchanged'
Assert ($global:CreepyGrantTestCalls[0].InputText -match 'fixture_admin_value') 'Credentials must go over stdin'
Assert (($global:CreepyGrantTestCalls.Arguments -join ' ') -notmatch 'fixture_admin_value|fixture_target_value') 'Credentials leaked into arguments'
Assert ($global:CreepyGrantTestCalls[2].Arguments -contains 'rm') 'Missing option file cleanup'
& "$root/tools/creepy-ro-grants.ps1"
Assert ($global:CreepyGrantTestCalls.Count -eq 6 -and $global:CreepyGrantTestCalls[4].InputText.Trim() -eq $sql.Trim()) 'Repeat run must send the same grants'
# Existing account-setup callers still substitute their target password.
. "$root/tools/db-user-common.ps1"
Set-Content "$root/scripts/fixture.sql" "SELECT '@FIXTURE@';"
$global:CreepyGrantTestCalls = New-Object System.Collections.Generic.List[object]
Invoke-DbUserSetup 'FIXTURE_PASSWORD' 'scripts/fixture.sql' '@FIXTURE@'
Assert ($global:CreepyGrantTestCalls[1].InputText -match "SELECT 'fixture_target_value';") 'Legacy password substitution broke'
Assert ($global:CreepyGrantTestCalls[1].InputText -match 'NO_BACKSLASH_ESCAPES') 'Legacy SQL mode handling broke'
# Errors propagate and the private option file is still removed.
$global:CreepyGrantTestCalls = New-Object System.Collections.Generic.List[object]; $global:CreepyGrantTestFailSql = $true; $failed = $false
try { & "$root/tools/creepy-ro-grants.ps1" } catch { $failed = $_.Exception.Message -eq 'SQL fehlgeschlagen' }
Assert $failed 'SQL failure must propagate'
Assert ($global:CreepyGrantTestCalls.Count -eq 3 -and $global:CreepyGrantTestCalls[2].Arguments -contains 'rm') 'SQL failure must clean up'
$global:CreepyGrantTestCalls = New-Object System.Collections.Generic.List[object]
Set-Content "$root/.env" 'MARIADB_ADMIN_USER=fixture_admin'
$failed = $false
try { & "$root/tools/creepy-ro-grants.ps1" } catch { $failed = $_.Exception.Message -eq 'MARIADB_ADMIN_PASSWORD fehlt in .env' }
Assert ($failed -and $global:CreepyGrantTestCalls.Count -eq 0) 'Missing admin credentials must fail before Docker'
Write-Host 'PASS: eight-grant boundary, admin-only credentials, legacy substitution, SQL failure cleanup, missing credentials.'
# Keep the isolated fixtures for inspection; no production resources were accessed.
