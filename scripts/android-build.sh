#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
IMAGE_NAME="${ATENEA_ANDROID_BUILDER_IMAGE:-atenea-android-builder:local}"
ANDROID_HOME_DIR="${ATENEA_ANDROID_HOME_DIR:-/srv/atenea/platform/secrets/android-home}"
APK_SECRET_FILE="${ATENEA_APK_SECRET_FILE:-/srv/atenea/platform/secrets/android-apk-download.env}"
GRADLE_TASK="${1:-:app:assembleDebug}"

shift || true

cd "$REPO_DIR"

install -d -m 700 "$ANDROID_HOME_DIR"

if [[ -f "$APK_SECRET_FILE" ]]; then
  # shellcheck source=/dev/null
  source "$APK_SECRET_FILE"
fi

EXTRA_GRADLE_ARGS=()
if [[ -n "${ATENEA_APK_DOWNLOAD_TOKEN:-}" && -z "${ATENEA_ANDROID_UPDATE_MANIFEST_URL:-}" ]]; then
  ATENEA_ANDROID_UPDATE_MANIFEST_URL="https://atenea.yudri.es/apk/${ATENEA_APK_DOWNLOAD_TOKEN}/android/manifest.json"
fi
if [[ -n "${ATENEA_ANDROID_UPDATE_MANIFEST_URL:-}" ]]; then
  EXTRA_GRADLE_ARGS+=("-PATENEA_ANDROID_UPDATE_MANIFEST_URL=${ATENEA_ANDROID_UPDATE_MANIFEST_URL}")
fi

docker build \
  -f docker/android-builder.Dockerfile \
  -t "$IMAGE_NAME" \
  .

docker run --rm \
  -v "$REPO_DIR:/workspace" \
  -v atenea-android-gradle-cache:/root/.gradle \
  -v "$ANDROID_HOME_DIR:/root/.android" \
  -w /workspace/android \
  "$IMAGE_NAME" \
  gradle "$GRADLE_TASK" "${EXTRA_GRADLE_ARGS[@]}" "$@"
