# Startet den PvP-Host-Dienst mit PVP_HOST_* und VIP_HOST_TOKEN aus ..\..\.env (Token wird nie ausgegeben).
# Stop-ScheduledTask beendet nur diese PowerShell, nicht ihr Kind python.exe (Port 25599 blieb belegt). Darum:
#  1. Beim Start: ein noch laufender Prozess mit unserer service.py-Kommandozeile (auch einer, der den Port haelt)
#     wird beendet und im Log vermerkt. Fremde Prozesse werden nie angefasst.
#  2. python.exe laeuft in einem Job-Objekt mit KILL_ON_JOB_CLOSE: stirbt diese PowerShell (Task stoppen, Abmelden,
#     Absturz), schliesst Windows das Job-Handle und beendet python.exe samt Kindern.
#  3. PID-Datei + taskkill /T im finally fuer den normalen Ausstieg.
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$envFile = Join-Path $root '.env'
if (Test-Path -LiteralPath $envFile) {
  foreach ($l in Get-Content $envFile) {
    if ($l -match '^(PVP_HOST_[A-Z_]+|VIP_HOST_TOKEN)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim(), 'Process') }
  }
}
Set-Location $root
$svc = Join-Path $PSScriptRoot 'service.py'
$py = 'C:\Program Files\Python311\python.exe'
$port = if ($env:PVP_HOST_PORT) { [int]$env:PVP_HOST_PORT } else { 25599 }
$logDir = Join-Path $env:LOCALAPPDATA 'mc-stack'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$log = Join-Path $logDir 'pvp-host-service.log'
$pidFile = Join-Path $logDir "pvp-host-service-$port.pid"
function Log($m) { Add-Content -LiteralPath $log -Value ("{0:s} {1}" -f (Get-Date), $m) }

# 1) stale instances of OUR service.py (command line contains this script's path), incl. the one holding the port
$mine = {
  param($p) $p.ProcessId -ne $PID -and $p.CommandLine -and $p.CommandLine.ToLower().Contains($svc.ToLower())
}
$stale = @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" | Where-Object { & $mine $_ })
foreach ($c in @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)) {
  if ($c.OwningProcess -and -not ($stale | Where-Object { $_.ProcessId -eq $c.OwningProcess })) {
    Log "port $port is held by PID $($c.OwningProcess), which is not our service.py - left alone"
  }
}
foreach ($p in $stale) {
  Log "stale service.py found (PID $($p.ProcessId)) - killing it and its children"
  & taskkill.exe /T /F /PID $p.ProcessId | Out-Null
}
if ($stale.Count) {
  for ($i = 0; $i -lt 20 -and (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue); $i++) { Start-Sleep -Milliseconds 250 }
}

# 2) python in a job object that dies with this process
Add-Type -TypeDefinition @'
using System; using System.Runtime.InteropServices;
public static class PvpJob {
  [StructLayout(LayoutKind.Sequential)] struct BASIC { public long a; public long b; public uint LimitFlags; public UIntPtr c; public UIntPtr d; public uint e; public UIntPtr f; public uint g; public uint h; }
  [StructLayout(LayoutKind.Sequential)] struct IOC { public ulong a, b, c, d, e, f; }
  [StructLayout(LayoutKind.Sequential)] struct EXT { public BASIC Basic; public IOC Io; public UIntPtr a, b, c, d; }
  [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern IntPtr CreateJobObject(IntPtr attr, string name);
  [DllImport("kernel32.dll")] static extern bool SetInformationJobObject(IntPtr job, int cls, IntPtr info, uint len);
  [DllImport("kernel32.dll")] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr proc);
  public static IntPtr Create() {
    IntPtr job = CreateJobObject(IntPtr.Zero, null);
    EXT ext = new EXT(); ext.Basic.LimitFlags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
    IntPtr mem = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(EXT)));
    Marshal.StructureToPtr(ext, mem, false);
    bool ok = SetInformationJobObject(job, 9, mem, (uint)Marshal.SizeOf(typeof(EXT)));
    Marshal.FreeHGlobal(mem);
    if (!ok) throw new System.ComponentModel.Win32Exception();
    return job;
  }
  public static void Add(IntPtr job, IntPtr proc) { if (!AssignProcessToJobObject(job, proc)) throw new System.ComponentModel.Win32Exception(); }
}
'@
$job = [PvpJob]::Create()   # stays referenced (and open) until this process ends
$proc = Start-Process -FilePath $py -ArgumentList "`"$svc`"" -NoNewWindow -PassThru
$null = $proc.Handle        # keep the handle: needed for the exit code
try {
  [PvpJob]::Add($job, $proc.Handle)
  Set-Content -LiteralPath $pidFile -Value $proc.Id
  Log "started service.py (PID $($proc.Id), port $port)"
  $proc.WaitForExit()
} finally {
  if (-not $proc.HasExited) { & taskkill.exe /T /F /PID $proc.Id | Out-Null; Log "stopped service.py (PID $($proc.Id))" }
  Remove-Item -LiteralPath $pidFile -ErrorAction SilentlyContinue
}
exit $proc.ExitCode
