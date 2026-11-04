"""Detect database passwords that are passed in process arguments.

Covered forms:

* shell-style flags in scripts: ``-p<pw>``, ``--password <pw>``, ``--password=<pw>``,
  ``MYSQL_PWD`` in ``-e``/``--env`` and ``@pw`` init files;
* the same flags as elements of an argument list - ``["mysql", "--password", pw]`` and
  ``["mariadb", "-p", pw]`` in Python, the inline and the one-element-per-line form of a
  compose ``command:``/``entrypoint:`` block, plus ``args:``/``run:`` in workflows;
* compose files (``compose*.yml``, ``docker-compose*.yml``) and the workflows under
  ``.github/workflows/``, including a literal (not ``${VAR}``/``${{ secrets.* }}``)
  ``MYSQL_PWD`` env value.

``-p`` only counts as a password flag for MySQL/MariaDB clients, so Docker's
``-p 8080:80`` port publishing is not reported. A password read from a secret file
(``--defaults-file``, ``--defaults-extra-file``, redirect) is not reported either.

Scanned: repository-root files, ``tools/`` and ``scripts/`` recursively (script
extensions), compose YAML anywhere in the tree and the workflow YAML of ``.github``.
Only ``path:line`` is printed, never the matched value.
"""
import re
from pathlib import Path

root = Path(__file__).resolve().parent.parent
self_path = Path(__file__).resolve()

EXTS = {'.ps1', '.py', '.sh', '.sql', '.cmd', '.bat'}
YAML_EXTS = {'.yml', '.yaml'}
# Directories holding generated or private content (build output, worlds, backups, logs)
SKIP_DIRS = {'.git', '.gradle', 'build', 'data', 'backups', 'logs', 'node_modules'}
# Clients whose single-dash -p means "password" (docker's -p is a port mapping)
DB_CLIENTS = {'mysql', 'mariadb', 'mysqldump', 'mariadb-dump', 'mysqladmin',
              'mariadb-admin', 'mysqlcheck', 'mariadb-check', 'mysqlimport',
              'mariadb-import', 'mysqlsh'}

# Line-level flags, used for script files, YAML scalars and block scalars.
TEXT_PATTERNS = [
    # --password <pw> / --password=<pw> (a bare --password asks interactively)
    re.compile(r"""(?i)(?:^|[\s'"`(;|&])--password(?:=|[ \t]+)["'`]?(?:[$(\[{]|[^\s"'-])"""),
    # -p<pw> attached form; a standalone "-p" element is handled by the list analyser
    re.compile(r"""(?:^|[\s'"`(;|&])-p(?=[${\w])[^\s"']*"""),
    re.compile(r"""(?i)(?:^|[\s'"`(;|&])(?:-e|--env)[ \t=]+["'`]?MYSQL_PWD="""),
    re.compile(r"""(?i)--init-command[^\n]*@pw"""),
    # literal MYSQL_PWD value in a YAML env mapping; ${VAR} and ${{ secrets.* }} stay clean
    re.compile(r"""(?i)^\s*MYSQL_PWD\s*:\s*(?!["']?\$(?:\{\{|\{|\w))[^\s#]"""),
]

TOKEN_RE = re.compile(r'"[^"\n]*"|\'[^\'\n]*\'|[^\s,\[\]()]+')
LIST_RE = re.compile(r'\[([^\[\]\n]*)\]')
YAML_KEY_RE = re.compile(
    r"""^(?P<indent>\s*)(?:-\s+)?(?P<key>command|entrypoint|args|test|run|shell)\s*:\s*(?P<rest>.*)$""",
    re.IGNORECASE)


def walk(directory):
    """Every file below directory, skipping generated and private directories."""
    for path in directory.rglob('*'):
        if path.is_file() and not SKIP_DIRS.intersection(path.relative_to(root).parts):
            yield path


def candidates():
    """Files that may pass database credentials: root, tools/, scripts/, compose, CI."""
    root_files = [p for p in root.glob('*') if p.is_file()]
    found = list(root_files)
    found += [p for d in (root / 'tools', root / 'scripts') if d.is_dir() for p in walk(d)]
    found += [p for p in root_files + list(walk(root))
              if p.suffix.lower() in YAML_EXTS and 'compose' in p.name.lower()]
    workflows = root / '.github' / 'workflows'
    if workflows.is_dir():
        found += [p for p in walk(workflows) if p.suffix.lower() in YAML_EXTS]
    return dict.fromkeys(found)


def tokens(text):
    """Split an argument list or a YAML sequence element into bare tokens."""
    result = []
    for raw in TOKEN_RE.findall(text):
        if len(raw) > 1 and raw[0] == raw[-1] and raw[0] in '"\'':
            raw = raw[1:-1]
        result.append(raw)
    return result


def program(token):
    """Basename of the command in a list, lowercased and without .exe."""
    name = token.replace('\\', '/').split('/')[-1].lower()
    return name[:-4] if name.endswith('.exe') else name


def flag_line(stream):
    """Line of the first password flag in [(lineno, token), ...] or None."""
    if not stream:
        return None
    prog = program(stream[0][1])
    for index, (lineno, token) in enumerate(stream[1:], 1):
        low = token.lower()
        has_value = index + 1 < len(stream)
        if low in ('--password', '--password='):
            # "--password", pw ; "--password=" + pw (concatenated value)
            concatenated = index + 2 < len(stream) and stream[index + 1][1] == '+'
            if has_value and (low == '--password' or concatenated):
                return lineno
            continue
        if low.startswith('--password=') and len(token) > len('--password='):
            return lineno
        # -p is only a password flag for a database client (docker uses it for ports)
        if prog in DB_CLIENTS and has_value and (low == '-p' or low.startswith('-p') and len(token) > 2):
            return lineno
    return None


def line_findings(number, text, allow_list):
    """Findings in one physical line: text flags plus an inline [...] list literal."""
    hits = set()
    if any(pattern.search(text) for pattern in TEXT_PATTERNS):
        hits.add(number)
    if allow_list and '[' in text:
        match = LIST_RE.search(text)
        if match:
            hit = flag_line([(number, token) for token in tokens(match.group(1))])
            if hit:
                hits.add(hit)
    return hits


def sequence_findings(lines, start, indent):
    """Findings in the '- element' sequence that follows a YAML key on its own line."""
    elements = []
    index = start
    while index < len(lines):
        current = lines[index]
        stripped = current.lstrip()
        deeper = len(current) - len(stripped) > indent
        if stripped and not (deeper and stripped.startswith('-')):
            break
        if stripped.startswith('-'):
            elements += [(index + 1, token) for token in tokens(stripped[1:].strip())]
        index += 1
    hit = flag_line(elements)
    return ({hit} if hit else set()), index


def yaml_findings(lines):
    """Findings in compose/workflow YAML, including multi-line command sequences."""
    hits = set()
    for number, text in enumerate(lines, 1):
        hits |= line_findings(number, text, allow_list=True)
    index = 0
    while index < len(lines):
        match = YAML_KEY_RE.match(lines[index])
        if not match or lines[index].lstrip().startswith('#'):
            index += 1
            continue
        indent = len(match.group('indent'))
        rest = match.group('rest').strip()
        if rest[:1] in ('>', '|'):
            # folded/literal block scalar: the following lines are one command string
            index += 1
            while index < len(lines):
                current = lines[index]
                if current.strip() and len(current) - len(current.lstrip()) <= indent:
                    break
                hits |= line_findings(index + 1, current, allow_list=False)
                index += 1
            continue
        if rest:
            index += 1
            continue
        found, index = sequence_findings(lines, index + 1, indent)
        hits |= found
    return hits


def scan(path):
    """Line numbers of findings in path."""
    try:
        text = path.read_text(encoding='utf-8-sig', errors='replace')
    except OSError:
        return set()
    lines = text.splitlines()
    if path.suffix.lower() in YAML_EXTS:
        return yaml_findings(lines)
    hits = set()
    for number, line in enumerate(lines, 1):
        hits |= line_findings(number, line, allow_list=True)
    return hits


failed = False
for path in candidates():
    if path == self_path or path.suffix.lower() not in EXTS | YAML_EXTS:
        continue
    for number in sorted(scan(path)):
        print(f'{path.relative_to(root).as_posix()}:{number}: possible credential in arguments')
        failed = True
if failed:
    raise SystemExit(1)
print('Database credential argument check passed.')
