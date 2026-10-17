"""Create local credentials, never overwrite an existing environment file."""
from pathlib import Path
import secrets
path = Path(__file__).with_name(".env")
with path.open("x", encoding="utf-8") as output:
    for key in ("KEYCLOAK_DB_PASSWORD", "USER_DB_PASSWORD", "KEYCLOAK_ADMIN_PASSWORD", "SMOKE_CLIENT_SECRET"):
        output.write(f"{key}={secrets.token_urlsafe(32)}\n")
print("Local .env created. Credentials are not printed and must not be committed.")
