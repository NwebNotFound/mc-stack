"""Detect common ways of putting database passwords in process arguments.

Covers shell-style flags (``-p<pw>``, ``--password <pw>``), the same flags as elements of
an argument list (``["mysql", "--password", pw]``), ``MYSQL_PWD`` in ``-e``/``--env``
options and ``@pw`` init files. Scanned: repository-root files, ``tools/`` and ``scripts/``
recursively, compose files (``compose*.yml``, ``docker-compose*.yml``) and the workflows
under ``.github/workflows/``.
"""
import re
from pathlib import Path

root = Path(__file__).resolve().parent.parent
patterns = [
    # -p case-sensitive (PowerShell parameters like -Path/-Pattern are not MariaDB flags)
    re.compile(r"(?:^|[\s'\"(])(?:-p|(?i:--password)(?:=|[\s]+))[\"']?(?:\$|\{|[^\s\"'$-]+)"),
    # the same flag as its own element of an argument list, e.g. ["mysql", "--password", pw]
    # (a -p element is already covered by the pattern above)
    re.compile(r"(?<![-\w])[\"'](?i:--password)[\"'][ \t]*,[ \t]*(?:[\"']|[${\w])"),
    re.compile(r"(?i)(?:-e|--env)(?:\s+|=)?[\"']?MYSQL_PWD="),
    re.compile(r"(?i)--init-command[^\n]*@pw"),
]
EXTS = {'.ps1', '.py', '.sh', '.sql', '.cmd', '.bat'}
YAML_EXTS = {'.yml', '.yaml'}
# Directories holding generated or private content (build output, worlds, backups, logs)
SKIP_DIRS = {'.git', '.gradle', 'build', 'data', 'backups', 'logs', 'node_modules'}
self_path = Path(__file__).resolve()


def walk(directory):
    """Every file below directory, skipping generated and private directories."""
    for path in directory.rglob('*'):
        if path.is_file() and not SKIP_DIRS.intersection(path.relative_to(root).parts):
            yield path


def candidates():
    """Files that may pass database credentials: repo root, tools/, scripts/, compose, CI."""
    root_files = [p for p in root.glob('*') if p.is_file()]
    found = root_files + [p for d in (root / 'tools', root / 'scripts') for p in walk(d)]
    found += [p for p in root_files + list(walk(root))
              if p.suffix.lower() in YAML_EXTS and 'compose' in p.name.lower()]
    workflows = root / '.github' / 'workflows'
    if workflows.is_dir():
        found += [p for p in walk(workflows) if p.suffix.lower() in YAML_EXTS]
    return dict.fromkeys(found)


failed = False
for path in candidates():
    if path == self_path or path.suffix.lower() not in EXTS | YAML_EXTS:
        continue
    for number, line in enumerate(path.read_text(encoding='utf-8-sig').splitlines(), 1):
        if any(pattern.search(line) for pattern in patterns):
            print(f'{path.relative_to(root)}:{number}: possible credential in arguments')
            failed = True
if failed:
    raise SystemExit(1)
print('Database credential argument check passed.')
