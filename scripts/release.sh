#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PUBLISH_APK="${ATENEA_RELEASE_PUBLISH_APK:-false}"

"$SCRIPT_DIR/test.sh"
"$SCRIPT_DIR/build.sh"
"$SCRIPT_DIR/deploy-preview.sh"
"$SCRIPT_DIR/deploy-prod.sh"

if [[ "$PUBLISH_APK" == "true" ]]; then
  "$SCRIPT_DIR/android-build.sh"
  "$SCRIPT_DIR/android-publish-apk.sh"
fi
