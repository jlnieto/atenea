#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SOURCE_APK="${REPO_DIR}/android/app/build/outputs/apk/debug/app-debug.apk"
PUBLIC_TARGET_DIR="/srv/atenea/apk-public"
PUBLIC_TARGET_APK="${PUBLIC_TARGET_DIR}/atenea-debug.apk"
SECRET_FILE="${ATENEA_APK_SECRET_FILE:-/srv/atenea/platform/secrets/android-apk-download.env}"

if [[ ! -f "$SOURCE_APK" ]]; then
  echo "APK not found: $SOURCE_APK" >&2
  echo "Run ./scripts/android-build.sh first." >&2
  exit 1
fi

install -d -m 755 "$PUBLIC_TARGET_DIR"
install -m 644 "$SOURCE_APK" "$PUBLIC_TARGET_APK"

if [[ -f "$SECRET_FILE" ]]; then
  # shellcheck source=/dev/null
  source "$SECRET_FILE"
fi

if [[ -n "${ATENEA_APK_DOWNLOAD_TOKEN:-}" ]]; then
  SECRET_TARGET_DIR="/srv/atenea/apk-public-secret/android"
  SECRET_TARGET_APK="${SECRET_TARGET_DIR}/atenea-debug.apk"
  SECRET_TARGET_MANIFEST="${SECRET_TARGET_DIR}/manifest.json"
  RELEASES_DIR="${SECRET_TARGET_DIR}/releases"
  APK_SHA256="$(sha256sum "$SOURCE_APK" | awk '{print $1}')"
  APK_SIZE_BYTES="$(stat -c '%s' "$SOURCE_APK")"
  VERSION_CODE="$(sed -n 's/.*versionCode = \([0-9][0-9]*\).*/\1/p' "${REPO_DIR}/android/app/build.gradle.kts" | head -1)"
  VERSION_NAME="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "${REPO_DIR}/android/app/build.gradle.kts" | head -1)"
  APK_URL="https://atenea.yudri.es/apk/${ATENEA_APK_DOWNLOAD_TOKEN}/android/atenea-debug.apk"
  RELEASE_TARGET_DIR="${RELEASES_DIR}/${VERSION_CODE}"
  RELEASE_TARGET_APK="${RELEASE_TARGET_DIR}/atenea-debug.apk"
  RELEASE_TARGET_MANIFEST="${RELEASE_TARGET_DIR}/manifest.json"
  PREVIOUS_MANIFEST_SOURCE=""
  PREVIOUS_APK_SOURCE=""

  install -d -m 755 "$SECRET_TARGET_DIR"
  if [[ -f "$SECRET_TARGET_MANIFEST" && -f "$SECRET_TARGET_APK" ]]; then
    PREVIOUS_CODE="$(python3 - "$SECRET_TARGET_MANIFEST" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as fh:
    print(json.load(fh).get("versionCode", ""))
PY
)"
    if [[ -n "$PREVIOUS_CODE" && "$PREVIOUS_CODE" != "$VERSION_CODE" ]]; then
      PREVIOUS_DIR="${RELEASES_DIR}/${PREVIOUS_CODE}"
      install -d -m 755 "$PREVIOUS_DIR"
      install -m 644 "$SECRET_TARGET_APK" "${PREVIOUS_DIR}/atenea-debug.apk"
      install -m 644 "$SECRET_TARGET_MANIFEST" "${PREVIOUS_DIR}/manifest.json"
      PREVIOUS_MANIFEST_SOURCE="${PREVIOUS_DIR}/manifest.json"
      PREVIOUS_APK_SOURCE="${PREVIOUS_DIR}/atenea-debug.apk"
    fi
  fi
  install -m 644 "$SOURCE_APK" "$SECRET_TARGET_APK"
  install -d -m 755 "$RELEASE_TARGET_DIR"
  install -m 644 "$SOURCE_APK" "$RELEASE_TARGET_APK"
  python3 - "$SECRET_TARGET_MANIFEST" "$RELEASE_TARGET_MANIFEST" "$PREVIOUS_MANIFEST_SOURCE" "$PREVIOUS_APK_SOURCE" <<PY
import json
import sys
from datetime import datetime, timezone

target = sys.argv[1]
release_target = sys.argv[2]
previous_manifest_source = sys.argv[3]
previous_apk_source = sys.argv[4]
created_at = datetime.now(timezone.utc).isoformat()
payload = {
    "versionCode": int("${VERSION_CODE:-1}"),
    "versionName": "${VERSION_NAME:-0.1.0}",
    "apkUrl": "${APK_URL}",
    "sha256": "${APK_SHA256}",
    "sizeBytes": int("${APK_SIZE_BYTES}"),
    "createdAt": created_at,
}
if previous_manifest_source and previous_apk_source:
    with open(previous_manifest_source, encoding="utf-8") as fh:
        previous = json.load(fh)
    previous_code = previous.get("versionCode")
    if previous_code:
        previous["apkUrl"] = "https://atenea.yudri.es/apk/${ATENEA_APK_DOWNLOAD_TOKEN}/android/releases/{}/atenea-debug.apk".format(previous_code)
        previous.pop("previousRelease", None)
        payload["previousRelease"] = previous
with open(target, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, ensure_ascii=False, indent=2)
    fh.write("\\n")
with open(release_target, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, ensure_ascii=False, indent=2)
    fh.write("\\n")
PY
  chmod 644 "$SECRET_TARGET_MANIFEST"
  chmod 644 "$RELEASE_TARGET_MANIFEST"
  echo "$SECRET_TARGET_APK"
  echo "$SECRET_TARGET_MANIFEST"
fi

echo "$PUBLIC_TARGET_APK"
