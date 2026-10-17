import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

# Les tests copient le script dans un dossier temporaire : le véritable .env n’est jamais modifié.
class InitEnvironmentTest(unittest.TestCase):
    # Régression : ces syntaxes dotenv ne doivent pas entraîner la rotation d’un secret existant.
    def test_upgrade_recognizes_dotenv_assignment_formats(self):
        for template in ("{key} = {value}", "export {key}={value}",
                         " \t export\t{key}\t = {value}"):
            with self.subTest(template=template), tempfile.TemporaryDirectory() as directory:
                root = pathlib.Path(directory)
                script = root / "init-env.py"
                shutil.copyfile(pathlib.Path(__file__).resolve().parents[2] / "infra/compose/init-env.py", script)
                existing = ("KEYCLOAK_DB_PASSWORD", "USER_DB_PASSWORD", "KEYCLOAK_ADMIN_PASSWORD", "SMOKE_CLIENT_SECRET")
                old = "\n".join(template.format(key=key, value="keep-" + key) for key in existing)
                # A commented assignment must not prevent generation of the missing secret.
                old += "\n  # SKILL_DB_PASSWORD=comment-only"
                env = root / ".env"
                env.write_text(old, encoding="utf-8")
                subprocess.run([sys.executable, str(script), "--upgrade"], check=True, capture_output=True)
                once = env.read_text(encoding="utf-8")
                self.assertTrue(once.startswith(old + "\n"))
                additions = once[len(old) + 1:].splitlines()
                self.assertEqual(1, len(additions))
                key, value = additions[0].split("=", 1)
                self.assertEqual("SKILL_DB_PASSWORD", key)
                self.assertGreaterEqual(len(value), 32)
                subprocess.run([sys.executable, str(script), "--upgrade"], check=True, capture_output=True)
                self.assertEqual(once, env.read_text(encoding="utf-8"))

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
