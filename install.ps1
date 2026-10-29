$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker wurde nicht gefunden. Docker Desktop installieren, starten und ein neues Terminal oeffnen.' }
docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker ist nicht erreichbar. Docker Desktop starten und erneut versuchen.' }
docker compose version *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose fehlt. Docker Desktop aktualisieren.' }
function New-Secret {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}
New-Item -ItemType Directory -Force -Path data/lobby, data/survival, data/velocity, secrets | Out-Null
if (-not (Test-Path -LiteralPath '.env')) {
    $template = Get-Content -Raw -LiteralPath '.env.example'
    $template = $template -replace '(?m)^RCON_PASSWORD=.*$', ('RCON_PASSWORD=' + (New-Secret))
    [System.IO.File]::WriteAllText((Join-Path $PSScriptRoot '.env'), $template, (New-Object System.Text.UTF8Encoding $false))
}
if (-not (Test-Path -LiteralPath 'secrets/forwarding.secret')) {
    [System.IO.File]::WriteAllText((Join-Path $PSScriptRoot 'secrets/forwarding.secret'), (New-Secret), (New-Object System.Text.UTF8Encoding $false))
}
docker compose up -d
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose konnte den Stack nicht starten.' }
Write-Host 'Start angefordert. Der erste Start kann einige Minuten dauern: docker compose ps'
