"""Jede Laufzeit-Datei in bot/ muss im Dockerfile kopiert werden (sonst ModuleNotFoundError erst im Container, vgl. #47)."""
import ast
import unittest
from pathlib import Path

HERE = Path(__file__).parent


def copied():
    out = set()
    for line in (HERE / "Dockerfile").read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if parts[:1] == ["COPY"] and not any(p.startswith("--from") for p in parts):
            out.update(parts[1:-1])
    return out


def local_imports(path):
    names = set()
    for node in ast.walk(ast.parse(path.read_text(encoding="utf-8-sig"))):
        if isinstance(node, ast.Import):
            names.update(a.name.split(".")[0] for a in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module and not node.level:
            names.add(node.module.split(".")[0])
    return {n for n in names if (HERE / f"{n}.py").exists()}


class DockerfileCopyTest(unittest.TestCase):
    def test_runtime_modules_copied(self):
        """Alle von bot.py (transitiv) importierten lokalen Module + changelog.json sind im Image."""
        need, todo = set(), ["bot"]
        while todo:
            m = todo.pop()
            if m in need:
                continue
            need.add(m)
            todo.extend(local_imports(HERE / f"{m}.py"))
        missing = sorted(f"{m}.py" for m in need if f"{m}.py" not in copied())
        self.assertEqual(missing, [], "Im Dockerfile-COPY fehlen: " + ", ".join(missing))
        self.assertIn("changelog.json", copied())

    def test_all_non_test_modules_copied(self):
        """Neue Module ohne Import aus bot.py (z.B. lazy per String) fallen sonst durch."""
        skip = {"commands"}  # keine .py-Datei, nur zur Sicherheit
        mods = {p.name for p in HERE.glob("*.py") if not p.name.startswith("test_") and p.stem not in skip}
        self.assertEqual(sorted(mods - copied()), [])


if __name__ == "__main__":
    unittest.main()
