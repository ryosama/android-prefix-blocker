#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
apk_path="${1:-$project_dir/dist/prefix-blocker-v1.0.0.apk}"
command -v adb >/dev/null || { echo "Installez les Android Platform Tools (adb)." >&2; exit 1; }
[[ -f "$apk_path" ]] || { echo "APK introuvable : $apk_path" >&2; exit 1; }

adb start-server
mapfile -t devices < <(adb devices | awk 'NR>1 && $2 == "device" {print $1}')
if [[ ${#devices[@]} -ne 1 ]]; then
    echo "Branchez et autorisez un seul téléphone, puis relancez ce script." >&2
    adb devices
    exit 1
fi

# adb allowlists restricted permissions by default; Android still asks the user
# for runtime access inside the application. Do not use --restrict-permissions.
adb -s "${devices[0]}" install -r "$apk_path"
adb -s "${devices[0]}" shell am start -n com.prefixblocker.app/.MainActivity
echo "Installation terminée. Choisissez prefix blocker comme service de filtrage dans l'application."
