import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

class InitEnvironmentTest(unittest.TestCase):
    def test_upgrade_preserves_all_existing_secrets_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            script = root / "init-env.py"
            shutil.copyfile(pathlib.Path(__file__).resolve().parents[2] / "infra/compose/init-env.py", script)
            old = "KEYCLOAK_DB_PASSWORD=keep-db\nUSER_DB_PASSWORD=keep-user\nKEYCLOAK_ADMIN_PASSWORD=keep-admin\nSMOKE_CLIENT_SECRET=keep-smoke"
            env = root / ".env"
            env.write_text(old, encoding="utf-8")
            subprocess.run([sys.executable, str(script), "--upgrade"], check=True, capture_output=True)
            once = env.read_text(encoding="utf-8")
            self.assertTrue(once.startswith(old + "\n"))
            self.assertIn("SKILL_DB_PASSWORD=", once)
            subprocess.run([sys.executable, str(script), "--upgrade"], check=True, capture_output=True)
            self.assertEqual(once, env.read_text(encoding="utf-8"))
            result = subprocess.run([sys.executable, str(script)], capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(once, env.read_text(encoding="utf-8"))

    def test_new_environment_contains_all_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            script = root / "init-env.py"
            shutil.copyfile(pathlib.Path(__file__).resolve().parents[2] / "infra/compose/init-env.py", script)
            subprocess.run([sys.executable, str(script)], check=True, capture_output=True)
            values = dict(line.split("=", 1) for line in (root / ".env").read_text().splitlines())
            self.assertEqual(5, len(values))
            self.assertTrue(all(len(value) >= 32 for value in values.values()))
