"""Create local credentials; --upgrade only adds missing keys without rotating existing values."""
import argparse
from pathlib import Path
import re
import secrets

# --upgrade complète un environnement existant ; sans cette option, la création doit être exclusive.
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--upgrade", action="store_true", help="Preserve existing credentials and append missing keys")
options = parser.parse_args()
# Cible le fichier voisin du script, quel que soit le répertoire courant de la commande.
path = Path(__file__).with_name(".env")
# Chaque base et chaque accès technique possèdent un secret séparé.
keys = ("KEYCLOAK_DB_PASSWORD", "USER_DB_PASSWORD", "KEYCLOAK_ADMIN_PASSWORD", "SMOKE_CLIENT_SECRET", "SKILL_DB_PASSWORD", "VIDEO_DB_PASSWORD", "MEDIA_WORKER_DB_PASSWORD")
if options.upgrade and path.exists():
    existing_text = path.read_text(encoding="utf-8")
    # Capture seulement les noms de variables, avec espaces ou export éventuels, sans lire leurs valeurs.
    # Les commentaires ne correspondent pas au motif et ne comptent pas comme des clés existantes.
    existing_keys = set(re.findall(
        r"^[ \t]*(?:export[ \t]+)?([A-Za-z_][A-Za-z0-9_]*)[ \t]*=",
        existing_text, re.MULTILINE,
    ))
    # Une clé reconnue conserve sa valeur : régénérer son secret casserait les bases déjà initialisées.
    missing = [key for key in keys if key not in existing_keys]
    if missing:
        with path.open("a", encoding="utf-8") as output:
            # Évite de coller la nouvelle déclaration à la dernière ligne du fichier.
            if existing_text and not existing_text.endswith("\n"):
                output.write("\n")
            for key in missing:
                # Génère un secret à partir de 32 octets aléatoires et ne l’affiche jamais.
                output.write(f"{key}={secrets.token_urlsafe(32)}\n")
    print("Local environment upgraded without changing existing credentials.")
else:
    # Le mode x refuse d’écraser un fichier existant, contrairement au mode w.
    with path.open("x", encoding="utf-8") as output:
        for key in keys:
            output.write(f"{key}={secrets.token_urlsafe(32)}\n")
    print("Local .env created. Credentials are not printed and must not be committed.")
