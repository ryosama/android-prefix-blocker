#!/usr/bin/env python3
"""Create a local, anonymous signing key. Never overwrite an existing key."""
import os
from pathlib import Path
import secrets
import shutil
import subprocess

root = Path(__file__).resolve().parent.parent
private = root / ".private"
private.mkdir(mode=0o700, exist_ok=True)
keystore = private / "release.jks"
properties = private / "signing.properties"
if keystore.exists() or properties.exists():
    raise SystemExit("Une clé ou sa configuration existe déjà. Aucun fichier modifié.")
keytool = shutil.which("keytool")
if not keytool and os.environ.get("JAVA_HOME"):
    keytool = str(Path(os.environ["JAVA_HOME"]) / "bin" / "keytool")
if not keytool:
    raise SystemExit("Installez un JDK 17 ou définissez JAVA_HOME.")
password = secrets.token_hex(32)
env = {**os.environ, "PREFIX_BLOCKER_KEY_PASSWORD": password}
subprocess.run([
    keytool, "-genkeypair", "-noprompt", "-keystore", str(keystore),
    "-storetype", "JKS", "-alias", "prefix-blocker", "-keyalg", "RSA", "-keysize", "3072",
    "-validity", "10000", "-dname", "CN=Prefix Blocker,O=Prefix Blocker,C=FR",
    "-storepass:env", "PREFIX_BLOCKER_KEY_PASSWORD", "-keypass:env", "PREFIX_BLOCKER_KEY_PASSWORD",
], env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
properties.write_text(
    "storeFile=.private/release.jks\n"
    f"storePassword={password}\nkeyAlias=prefix-blocker\nkeyPassword={password}\n",
    encoding="utf-8",
)
keystore.chmod(0o600)
properties.chmod(0o600)
print("Clé anonyme créée dans .private/ (exclue de Git). Conservez ce dossier pour les mises à jour.")
