#!/usr/bin/env python3
"""Check tracked files and an APK for accidentally embedded local identifiers.

Additional private strings (one per line) may be provided in the ignored file
.private/privacy-patterns.txt. Matching data is never printed.
"""
import os
from pathlib import Path
import socket
import subprocess
import sys
import zipfile

root = Path(__file__).resolve().parent.parent
patterns = {str(Path.home()), str(root), socket.gethostname()}
private_patterns = root / ".private/privacy-patterns.txt"
if private_patterns.exists():
    patterns.update(private_patterns.read_text(encoding="utf-8").splitlines())
patterns = {p for p in patterns if len(p) >= 4}
needles = [p.encode(encoding) for p in patterns for encoding in ("utf-8", "utf-16le", "utf-16be")]
failures = []

def check(label, data):
    if any(needle in data for needle in needles):
        failures.append(label)

files = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
for relative in filter(None, files):
    path = root / relative
    if relative.startswith((".private/", ".gradle/")) or path.suffix in (".jks", ".keystore") or relative == "local.properties":
        failures.append(relative)
    check(relative, path.read_bytes())

for argument in sys.argv[1:]:
    apk = Path(argument)
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            check(f"{apk.name}:{name}", archive.read(name))

if failures:
    print("Contrôle refusé : identifiant local ou fichier privé détecté dans :")
    print("\n".join(sorted(set(failures))))
    raise SystemExit(1)
print("Contrôle réussi : aucun identifiant local recherché ni fichier privé suivi.")
