import os, re, subprocess, sys
from pathlib import Path

root = Path(__file__).resolve().parent.parent
values = {}
for line in (root / '.env').read_text(encoding='utf-8-sig').splitlines():
    m = re.match(r'^\s*(LUCKPERMS_DB_USER|LUCKPERMS_DB_PASSWORD)=(.*)\s*$', line)
    if m:
        value = m.group(2).strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        if m.group(1) in values: raise SystemExit('Doppelte LuckPerms-Zugangsdaten.')
        values[m.group(1)] = value
if set(values) != {'LUCKPERMS_DB_USER', 'LUCKPERMS_DB_PASSWORD'} or any(not v or '\n' in v or '\r' in v for v in values.values()):
    raise SystemExit('LuckPerms-Zugangsdaten fehlen oder sind ungueltig.')
check = len(sys.argv) == 2 and sys.argv[1] == '--check'
if not check and len(sys.argv) != 2: raise SystemExit('Zielpfad fehlt.')
command = 'read -r u; read -r p; export MYSQL_PWD="$p"; exec mariadb-dump -h 127.0.0.1 -u "$u" --single-transaction --quick --no-tablespaces --skip-lock-tables luckperms'
args = ['docker', 'compose', 'exec', '-T', 'craftcore-db', 'sh', '-c', command]
with (open(os.devnull, 'wb') if check else open(sys.argv[1], 'xb')) as output:
    proc = subprocess.run(args, input=(values['LUCKPERMS_DB_USER']+'\n'+values['LUCKPERMS_DB_PASSWORD']+'\n').encode(), stdout=output, stderr=subprocess.DEVNULL, cwd=root)
if proc.returncode: raise SystemExit('LuckPerms-Datenbankzugriff fehlgeschlagen.')
if not check and (Path(sys.argv[1]).stat().st_size < 100 or b'-- Dump completed' not in Path(sys.argv[1]).read_bytes()[-4096:]): raise SystemExit('LuckPerms-Dump unvollstaendig.')
