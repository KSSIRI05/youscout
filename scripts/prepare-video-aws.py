"""Prepare temporary Video-role credentials for local Docker; never display credentials."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_ROLE = "arn:aws:sts::014663857971:assumed-role/YouScoutVideo/"


def cli_json(aws, *args):
    result = subprocess.run([aws, *args, "--no-cli-pager"], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError("AWS CLI a échoué ; vérifier la session du profil Video sans afficher ses identifiants.")
    return json.loads(result.stdout)


def prepare(aws, profile):
    identity = cli_json(aws, "sts", "get-caller-identity", "--profile", profile)
    if identity.get("Account") != "014663857971" or not identity.get("Arn", "").startswith(EXPECTED_ROLE):
        raise RuntimeError("Seul le rôle YouScoutVideo peut être transmis au conteneur.")
    credentials = cli_json(aws, "configure", "export-credentials", "--profile", profile, "--format", "process")
    if not all(credentials.get(key) for key in ("AccessKeyId", "SecretAccessKey", "SessionToken", "Expiration")):
        raise RuntimeError("Des identifiants temporaires complets avec expiration sont requis.")
    expiration = datetime.fromisoformat(credentials["Expiration"].replace("Z", "+00:00"))
    if (expiration - datetime.now(timezone.utc)).total_seconds() < 360:
        raise RuntimeError("Session trop proche de son expiration ; renouveler le profil Video puis réessayer.")
    directory = ROOT / "infra/compose/.aws"
    directory.mkdir(mode=0o700, exist_ok=True)
    if os.name == "nt":
        identity_result = subprocess.run(["whoami", "/user", "/fo", "csv", "/nh"], capture_output=True, text=True, check=True)
        sid = re.search(r"S-1-[0-9-]+", identity_result.stdout).group()
        subprocess.run(["icacls", str(directory), "/inheritance:r", "/grant:r",
                        f"*{sid}:(OI)(CI)F", "*S-1-5-18:(OI)(CI)F"], capture_output=True, check=True)
    else:
        directory.chmod(0o700)
    # Le montage du dossier permet au fournisseur process de relire le fichier après renouvellement.
    temporary = directory / "credentials.json.tmp"
    with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w", encoding="utf-8") as output:
        json.dump(credentials, output)
    os.replace(temporary, directory / "credentials.json")
    config = directory / "config"
    config.write_text("[profile video-container]\ncredential_process = cat /run/youscout-aws/credentials.json\nregion = us-east-1\n", encoding="utf-8")
    print("Identifiants temporaires YouScoutVideo préparés hors Git ; expiration : " + expiration.isoformat())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    fallback = Path.home() / "AppData/Local/Programs/Amazon/AWSCLIV2/aws.exe"
    parser.add_argument("--aws", default=shutil.which("aws") or str(fallback))
    parser.add_argument("--profile", default="youscout-video-sdk")
    args = parser.parse_args()
    try:
        prepare(args.aws, args.profile)
    except (RuntimeError, OSError, ValueError, subprocess.SubprocessError):
        raise SystemExit("Préparation refusée : vérifier la connexion du rôle YouScoutVideo, l'expiration et les permissions locales.") from None


if __name__ == "__main__":
    main()
