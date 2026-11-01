"""Detect common ways of putting database passwords in process arguments."""
import re
from pathlib import Path

root = Path(__file__).resolve().parent.parent
patterns = [
    # -p case-sensitive (PowerShell parameters like -Path/-Pattern are not MariaDB flags)
    re.compile(r"(?:^|[\s'\"(])(?:-p|(?i:--password)(?:=|\s+))[\"\']?(?:\$|\{|[^\s\"\'$-]+)"),
    re.compile(r"(?i)(?:-e|--env)(?:\s+|=)?[\"\']?MYSQL_PWD="),
    re.compile(r"(?i)--init-command[^\n]*@pw"),
]
EXTS = {'.ps1', '.py', '.sh', '.sql', '.cmd', '.bat'}
failed = False
# Repository-root scripts (non-recursive) plus tools/ and scripts/ (recursive)
candidates = [p for p in root.glob('*') if p.is_file()] + [p for d in (root / 'tools', root / 'scripts') for p in d.rglob('*')]
for _ in (0,):
    for path in candidates:
        if not path.is_file() or path == Path(__file__).resolve() or path.suffix.lower() not in EXTS:
            continue
        for number, line in enumerate(path.read_text(encoding='utf-8-sig').splitlines(), 1):
            if any(pattern.search(line) for pattern in patterns):
                print(f'{path.relative_to(root)}:{number}: possible credential in arguments')
                failed = True
if failed:
    raise SystemExit(1)
print('Database credential argument check passed.')
