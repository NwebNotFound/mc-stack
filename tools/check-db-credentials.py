"""Detect common ways of putting database passwords in process arguments."""
import re
from pathlib import Path

root = Path(__file__).resolve().parent.parent
patterns = [
    re.compile(r"(?i)(?:^|[\s'\"(])(?:-p|--password(?:=|\s+))(?:[\"\']?\$|[\"\']?\{|[^\s\"\'$-]+)"),
    re.compile(r"(?i)(?:-e|--env)\s+[\"\']?MYSQL_PWD="),
    re.compile(r"(?i)--init-command[^\n]*@pw"),
]
failed = False
for directory in (root / 'tools', root / 'scripts'):
    for path in directory.rglob('*'):
        if not path.is_file() or path == Path(__file__).resolve() or path.suffix.lower() not in {'.ps1', '.py', '.sh', '.sql', '.cmd', '.bat'}:
            continue
        for number, line in enumerate(path.read_text(encoding='utf-8-sig').splitlines(), 1):
            if any(pattern.search(line) for pattern in patterns):
                print(f'{path.relative_to(root)}:{number}: possible credential in arguments')
                failed = True
if failed:
    raise SystemExit(1)
print('Database credential argument check passed.')
