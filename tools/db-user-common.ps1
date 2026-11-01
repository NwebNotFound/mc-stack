# Shared implementation for the restricted CraftCore database accounts.
function Invoke-DbUserSetup([string]$PasswordKey, [string]$SqlFile, [string]$Placeholder) {
  $ErrorActionPreference = 'Stop'
  $root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
  $vals = @{}
  foreach ($line in Get-Content -LiteralPath (Join-Path $root '.env') -Encoding UTF8) {
    if ($line -match '^([A-Z0-9_]+)=(.*)$') {
      $value = $Matches[2].Trim()
      if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[$value.Length-1] -eq '"') -or ($value[0] -eq "'" -and $value[$value.Length-1] -eq "'"))) { $value = $value.Substring(1, $value.Length-2) }
      $vals[$Matches[1]] = $value
    }
  }
  foreach ($key in @('MARIADB_ADMIN_USER', 'MARIADB_ADMIN_PASSWORD', $PasswordKey)) {
    if (-not $vals[$key]) { throw "$key fehlt in .env" }
    if ($vals[$key] -match '[\r\n\x00]') { throw "$key enthaelt unzulaessige Zeichen" }
  }
  # MariaDB option files interpret backslash escapes, including inside quotes.
  $user = $vals['MARIADB_ADMIN_USER'].Replace('\', '\\').Replace('"', '\"')
  $password = $vals['MARIADB_ADMIN_PASSWORD'].Replace('\', '\\').Replace('"', '\"')
  $optionFile = "[client]`nuser=`"$user`"`npassword=`"$password`"`n"
  # Force normal backslash escaping in this session before substituting a SQL literal.
  $escaped = $vals[$PasswordKey].Replace('\', '\\').Replace("'", "''")
  $sql = "SET SESSION sql_mode = REPLACE(@@SESSION.sql_mode, 'NO_BACKSLASH_ESCAPES', '');`n" +
    (Get-Content -LiteralPath (Join-Path $root $SqlFile) -Raw -Encoding UTF8).Replace($Placeholder, $escaped)
  $path = '/tmp/craftcore-db-' + [Guid]::NewGuid().ToString('N') + '.cnf'
  $oldEncoding = $OutputEncoding
  $OutputEncoding = New-Object System.Text.UTF8Encoding $false
  try {
    $optionFile | docker compose --project-directory $root exec -T craftcore-db sh -c 'umask 077; cat > "$1"' sh $path 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'DB-Optionsdatei konnte nicht erstellt werden' }
    $sql | docker compose --project-directory $root exec -T craftcore-db mariadb "--defaults-extra-file=$path" craftcore 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'SQL fehlgeschlagen' }
  } finally {
    docker compose --project-directory $root exec -T craftcore-db rm -f -- $path 2>$null | Out-Null
    $cleanupExit = $LASTEXITCODE
    $OutputEncoding = $oldEncoding
    if ($cleanupExit -ne 0) { throw "DB-Optionsdatei konnte nicht geloescht werden: $path" }
  }
}
