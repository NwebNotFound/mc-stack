"""Regressionstests fuer tools/check-db-credentials.py (#99).

Geprueft werden die drei Luecken aus #99 - Passwort-Flags in Python-Argumentlisten,
``compose*.yml``/``docker-compose*.yml`` (inline und als ein Element pro Zeile) und
Workflow-Dateien unter ``.github/workflows/`` - plus die Gegenproben: Docker-``-p``
als Port-Flag, interaktives ``--password`` ohne Wert, Passwoerter aus Secret-Dateien
und aus ``${VAR}``/``${{ secrets.* }}`` duerfen keinen Fund ergeben.

Der Scanner wird wie in der CI als Subprozess gegen eine temporaere Baumkopie
ausgefuehrt. Die Flag-Literale werden absichtlich aus Teilen zusammengesetzt, weil
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
PWD_ENV = "MYSQL_" + "PWD"                              # Name der Env-Variable
SECRET_EXPR = "$" + "{{ secrets.DB_PASSWORD }}"         # GitHub-Actions-Secret
SENTINEL = "sen" + "tinel-value-4f2a"                   # Marker, darf nie im Output stehen


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
        # Der Fund nennt nur die Stelle, nie den Wert.
        self.assertNotIn(SENTINEL, output)

    def assert_clean(self):
        code, output = self.scan()
        self.assertEqual(0, code, output)

    # --- Fall 1: Argumentlisten in Python-Subprozessen -------------------------
    def test_python_argument_list_long_flag(self):
        self.write("scripts/migrate.py",
                   "import subprocess\n\n"
                   f'subprocess.run(["mysql", "{LONG_FLAG}", "{SENTINEL}"])\n')
        self.assert_reported("scripts/migrate.py:3")

    def test_python_argument_list_short_flag(self):
        self.write("scripts/migrate.py",
                   "import subprocess\n\n"
                   f'subprocess.run(["mariadb", "-uroot", "{SHORT_FLAG}", password])\n')
        self.assert_reported("scripts/migrate.py:3")

    def test_python_argument_list_equals_form(self):
        self.write("scripts/migrate.py",
                   "import subprocess\n\n"
                   f'subprocess.run(["mariadb-dump", "{LONG_FLAG}=" + "{SENTINEL}"])\n')
        self.assert_reported("scripts/migrate.py:3")

    # --- Fall 2: compose.yaml / docker-compose*.yml ----------------------------
    def test_compose_inline_command_short_flag(self):
        self.write("compose.yaml",
                   "services:\n  db:\n"
                   f"    command: mariadb -u root {SHORT_FLAG}{ENV_REF} craftcore\n")
        self.assert_reported("compose.yaml:3")

    def test_compose_sequence_command_short_flag(self):
        """Ein Element pro Zeile - das Passwort-Flag steht allein in seiner Zeile."""
        self.write("compose.yaml",
                   "services:\n  db:\n    image: mariadb:11\n    command:\n"
                   "      - mariadb\n      - -u\n      - root\n"
                   f"      - {SHORT_FLAG}\n      - {ENV_REF}\n")
        self.assert_reported("compose.yaml:8")

    def test_compose_inline_list_long_flag(self):
        self.write("docker-compose.override.yml",
                   "services:\n  db:\n"
                   f'    command: ["mariadb", "{LONG_FLAG}", "{ENV_REF}"]\n')
        self.assert_reported("docker-compose.override.yml:3")

    # --- Fall 3: Workflows unter .github/workflows/ ---------------------------
    def test_workflow_run_step_short_flag(self):
        self.write(".github/workflows/backup.yml",
                   "name: Backup\non: [push]\njobs:\n  backup:\n    runs-on: ubuntu-latest\n"
                   f"    steps:\n      - run: mariadb -u root {SHORT_FLAG}{ENV_REF} < dump.sql\n")
        self.assert_reported(".github/workflows/backup.yml:7")

    def test_workflow_env_literal_password(self):
        self.write(".github/workflows/backup.yml",
                   "name: Backup\non: [push]\njobs:\n  backup:\n    runs-on: ubuntu-latest\n"
                   "    steps:\n      - run: echo start\n        env:\n"
                   f"          {PWD_ENV}: {SENTINEL}\n")
        self.assert_reported(".github/workflows/backup.yml:9")

    def test_workflow_step_args_list(self):
        self.write(".github/workflows/check.yml",
                   "name: Check\non: [push]\njobs:\n  check:\n    runs-on: ubuntu-latest\n"
                   "    steps:\n      - uses: docker://mariadb:11\n        with:\n"
                   f"          args: mariadb -u root {SHORT_FLAG}{ENV_REF} -e SELECT 1\n")
        self.assert_reported(".github/workflows/check.yml:9")

    # --- Gegenproben: das darf kein Fund sein --------------------------------
    def test_docker_port_flag_is_not_reported(self):
        self.write("scripts/run.py",
                   "import subprocess\n\n"
                   'subprocess.run(["docker", "run", "-p", "127.0.0.1:8080:80", "img"])\n')
        self.write("compose.yaml",
                   "services:\n  web:\n    image: nginx\n"
                   '    ports:\n      - "127.0.0.1:8080:80"\n'
                   "    command: docker run -p 8080:80 nginx\n"
                   "    entrypoint:\n      - docker\n      - run\n      - -p\n      - 8080:80\n")
        self.write(".github/workflows/smoke.yml",
                   "name: Smoke\non: [push]\njobs:\n  smoke:\n    runs-on: ubuntu-latest\n"
                   "    steps:\n      - run: docker run -p 5432:5432 postgres:16\n")
        self.assert_clean()

    def test_password_from_env_or_secret_file_is_not_reported(self):
        self.write("compose.yaml",
                   "services:\n  db:\n    image: mariadb:11\n    environment:\n"
                   "      MARIADB_ROOT_PASSWORD_FILE: /run/secrets/db_root\n"
                   f"      {PWD_ENV}: {ENV_REF}\n")
        self.write(".github/workflows/ci.yml",
                   "name: CI\non: [push]\njobs:\n  db:\n    runs-on: ubuntu-latest\n"
                   "    steps:\n"
                   "      - run: mariadb-dump --defaults-extra-file=/run/secrets/db.cnf craftcore\n"
                   "      - run: mysql < /run/secrets/dump.sql\n"
                   "        env:\n"
                   f"          {PWD_ENV}: {SECRET_EXPR}\n")
        self.write("scripts/backup.sh",
                   "mariadb-dump --defaults-file=/etc/mysql/backup.cnf craftcore > craftcore.sql\n"
                   "mariadb -u root -e 'SHOW DATABASES'\n")
        self.assert_clean()

    def test_interactive_password_prompt_is_not_reported(self):
        self.write("scripts/interactive.sh", f"mariadb -u root {LONG_FLAG}\n")
        self.write("scripts/interactive.py",
                   "import subprocess\n"
                   f'subprocess.run(["mariadb", "-u", "root", "{LONG_FLAG}"])\n')
        self.assert_clean()

    def test_reported_paths_use_forward_slashes(self):
        """Fundstellen sind auf Windows und Linux gleich geschrieben (scripts/...)."""
        self.write("scripts/migrate.py",
                   f'subprocess.run(["mysql", "{LONG_FLAG}", "{SENTINEL}"])\n')
        self.assert_reported("scripts/migrate.py:1")

    def test_tracked_tree_still_clean(self):
        """Der Repo-Stand liefert keine Funde - schuetzt vor False Positives."""
        code, output = self.scan(ROOT)
        self.assertEqual(0, code, output)
        self.assertIn("check passed", output)


if __name__ == "__main__":
    unittest.main()
