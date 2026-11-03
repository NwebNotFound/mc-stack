"""Regressionstests fuer tools/check-db-credentials.py (#99).

Geprueft werden die drei Luecken aus #99: Passwort-Flags in Python-Argumentlisten,
compose.yaml bzw. docker-compose*.yml und Workflow-Dateien unter .github/workflows/.
Der Scanner wird wie in der CI als Subprozess gegen eine temporaere Baumkopie
ausgefuehrt; die Flag-Literale werden absichtlich aus Teilen zusammengesetzt, weil
diese Datei selbst im gescannten tools/ liegt.
"""
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
SCANNER = HERE / "check-db-credentials.py"
SHORT_FLAG = "-" + "p"                                  # MariaDB-Kurzform des Passwort-Flags
LONG_FLAG = "--" + "password"                           # MariaDB-Langform des Passwort-Flags
ENV_REF = "${" + "DB_" + "PASSWORD" + "}"               # Platzhalter, nie ein echtes Secret


class ScannerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "tools").mkdir()
        shutil.copy(SCANNER, self.root / "tools" / SCANNER.name)

    def write(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def scan(self, tree=None):
        result = subprocess.run([sys.executable, str((tree or self.root) / "tools" / SCANNER.name)],
                                capture_output=True, text=True)
        return result.returncode, result.stdout + result.stderr

    def assert_reported(self, expected):
        code, output = self.scan()
        self.assertEqual(1, code, output)
        self.assertIn(expected, output)

    def test_python_argument_list_long_flag(self):
        self.write("scripts/migrate.py",
                   f'import subprocess\n\nsubprocess.run(["mysql", "{LONG_FLAG}", password])\n')
        self.assert_reported("scripts/migrate.py:3")

    def test_python_argument_list_short_flag(self):
        self.write("scripts/migrate.py",
                   f'import subprocess\n\nsubprocess.run(["mysql", "-uroot", "{SHORT_FLAG}", password])\n')
        self.assert_reported("scripts/migrate.py:3")

    def test_compose_command_short_flag(self):
        self.write("compose.yaml",
                   f'services:\n  db:\n    command: mariadb -u root {SHORT_FLAG}{ENV_REF} craftcore\n')
        self.assert_reported("compose.yaml:3")

    def test_compose_command_list_long_flag(self):
        self.write("docker-compose.override.yml",
                   f'services:\n  db:\n    command: ["mariadb", "{LONG_FLAG}", "{ENV_REF}"]\n')
        self.assert_reported("docker-compose.override.yml:3")

    def test_workflow_run_step_short_flag(self):
        self.write(".github/workflows/backup.yml",
                   "name: Backup\non: [push]\njobs:\n  backup:\n    runs-on: ubuntu-latest\n"
                   f"    steps:\n      - run: mariadb -u root {SHORT_FLAG}{ENV_REF} < dump.sql\n")
        self.assert_reported(".github/workflows/backup.yml:7")

    def test_clean_files_are_not_reported(self):
        self.write("compose.yaml", "services:\n  db:\n    image: mariadb:11\n    environment:\n"
                                   "      MARIADB_ROOT_PASSWORD_FILE: /run/secrets/db_root\n")
        self.write(".github/workflows/ci.yml",
                   "name: CI\non: [push]\njobs:\n  db-credentials:\n    runs-on: ubuntu-latest\n"
                   "    steps:\n      - run: python3 tools/check-db-credentials.py\n")
        self.write("scripts/backup.sh",
                   "mariadb-dump --defaults-file=/etc/mysql/backup.cnf craftcore > craftcore.sql\n")
        # Ein Flag ohne Wert fragt interaktiv nach dem Passwort und ist kein Fund.
        self.write("scripts/interactive.sh", f"mariadb -u root {LONG_FLAG}\n")
        code, output = self.scan()
        self.assertEqual(0, code, output)

    def test_tracked_tree_still_clean(self):
        """Der Repo-Stand darf keine (neuen) Treffer liefern - schuetzt vor False Positives."""
        code, output = self.scan(ROOT)
        self.assertEqual(0, code, output)


if __name__ == "__main__":
    unittest.main()
