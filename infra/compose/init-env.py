"""Create local credentials; --upgrade only adds missing keys without rotating existing values."""
import argparse
from pathlib import Path
import secrets

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--upgrade", action="store_true", help="Preserve existing credentials and append missing keys")
options = parser.parse_args()
path = Path(__file__).with_name(".env")
keys = ("KEYCLOAK_DB_PASSWORD", "USER_DB_PASSWORD", "KEYCLOAK_ADMIN_PASSWORD", "SMOKE_CLIENT_SECRET", "SKILL_DB_PASSWORD")
if options.upgrade and path.exists():
    existing_text = path.read_text(encoding="utf-8")
    existing_keys = {line.split("=", 1)[0] for line in existing_text.splitlines() if line and not line.startswith("#")}
    missing = [key for key in keys if key not in existing_keys]
    if missing:
        with path.open("a", encoding="utf-8") as output:
            if existing_text and not existing_text.endswith("\n"):
                output.write("\n")
            for key in missing:
                output.write(f"{key}={secrets.token_urlsafe(32)}\n")
    print("Local environment upgraded without changing existing credentials.")
else:
    with path.open("x", encoding="utf-8") as output:
        for key in keys:
            output.write(f"{key}={secrets.token_urlsafe(32)}\n")
    print("Local .env created. Credentials are not printed and must not be committed.")
