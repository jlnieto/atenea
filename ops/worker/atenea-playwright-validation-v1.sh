#!/usr/bin/env bash
set -euo pipefail

SESSION_ID="${1:-}"
VALIDATION_ID="${2:-}"
SOURCE_ROOT="${3:-}"
ARTIFACT_ROOT="${4:-}"
IMAGE="mcr.microsoft.com/playwright:v1.60.0-noble@sha256:9bd26ad900bb5e0f4dee75839e957a89ae89c2b7ab1e76050e559790e946b948"
CHECK="/usr/local/libexec/atenea/atenea-playwright-validation-v1.js"

fail() {
  printf 'Playwright validation rejected\n' >&2
  exit 64
}

[[ "$#" -eq 4 && "$(id -u)" -eq 0 ]] || fail
UUID_RE='^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
[[ "$SESSION_ID" =~ $UUID_RE && "$VALIDATION_ID" =~ $UUID_RE ]] || fail
EXPECTED_SOURCE="/srv/atenea/artifacts/validations/${SESSION_ID}/.validation-run."
[[ "$SOURCE_ROOT" == "$EXPECTED_SOURCE"*/repo && -d "$SOURCE_ROOT" && ! -L "$SOURCE_ROOT" ]] || fail
[[ "$ARTIFACT_ROOT" == "/srv/atenea/artifacts/validations/${SESSION_ID}/${VALIDATION_ID}" ]] || fail
[[ ! -e "$ARTIFACT_ROOT" || (-d "$ARTIFACT_ROOT" && ! -L "$ARTIFACT_ROOT") ]] || fail
[[ -f "$CHECK" && ! -L "$CHECK" && "$(stat -c '%U:%G:%a' "$CHECK")" == "root:root:644" ]] || fail

ALLOCATION="/srv/atenea/workspaces/sessions/${SESSION_ID}/runtime-allocation-v1.json"
[[ -f "$ALLOCATION" && ! -L "$ALLOCATION" ]] || fail
jq -e --arg session "$SESSION_ID" '.sessionId == $session' "$ALLOCATION" >/dev/null || fail
SLOT="$(jq -r '.slot' "$ALLOCATION")"
[[ "$SLOT" =~ ^slot[1-4]$ ]] || fail
SLOT_USER="atenea-${SLOT}"
SLOT_HOME="$(getent passwd "$SLOT_USER" | cut -d: -f6)"
SLOT_UID="$(id -u "$SLOT_USER")"
RUNTIME_DIR="/run/user/${SLOT_UID}"
SOCKET="${RUNTIME_DIR}/docker.sock"
MODULE="${SLOT_HOME}/toolchain/playwright-module-v1"
[[ -S "$SOCKET" && -d "$MODULE/node_modules/playwright" && ! -L "$MODULE" ]] || fail
SLOT_ARTIFACT="$(mktemp -d "$SLOT_HOME/.atenea-playwright-${VALIDATION_ID}.XXXXXX")"
SLOT_SOURCE="$(mktemp -d "$SLOT_HOME/.atenea-playwright-source-${VALIDATION_ID}.XXXXXX")"
chown "$SLOT_USER:$SLOT_USER" "$SLOT_ARTIFACT"
chown "$SLOT_USER:$SLOT_USER" "$SLOT_SOURCE"
cleanup_slot_artifact() {
  case "$SLOT_ARTIFACT" in
    "$SLOT_HOME"/.atenea-playwright-"$VALIDATION_ID".*)
      rm -rf -- "$SLOT_ARTIFACT"
      ;;
  esac
  case "$SLOT_SOURCE" in
    "$SLOT_HOME"/.atenea-playwright-source-"$VALIDATION_ID".*)
      rm -rf -- "$SLOT_SOURCE"
      ;;
  esac
}
trap cleanup_slot_artifact EXIT

mkdir "$SLOT_SOURCE/repo"
cp -a "$SOURCE_ROOT/." "$SLOT_SOURCE/repo/"
(cd "$SLOT_SOURCE/repo" && timeout --signal=TERM --kill-after=15s 300s ./scripts/web-build.sh)
STATIC="$SLOT_SOURCE/repo/src/main/resources/static"
[[ -f "$STATIC/index.html" ]] || fail
chown -R "$SLOT_USER:$SLOT_USER" "$SLOT_SOURCE"
chmod 0700 "$SLOT_SOURCE"
install -d -o root -g root -m 0750 "$ARTIFACT_ROOT"
NAME="atenea-playwright-${VALIDATION_ID//-/}"
docker_slot() {
  runuser -u "$SLOT_USER" -- env \
    HOME="$SLOT_HOME" XDG_RUNTIME_DIR="$RUNTIME_DIR" DOCKER_HOST="unix://$SOCKET" \
    docker "$@"
}
if docker_slot container inspect "$NAME" >/dev/null 2>&1; then
  fail
fi

set +e
timeout --signal=TERM --kill-after=15s 300s \
  runuser -u "$SLOT_USER" -- env \
    HOME="$SLOT_HOME" XDG_RUNTIME_DIR="$RUNTIME_DIR" DOCKER_HOST="unix://$SOCKET" \
    docker run --rm \
      --name "$NAME" \
      --label com.atenea.validation=playwright-v1 \
      --label "com.atenea.session-id=$SESSION_ID" \
      --label "com.atenea.validation-id=$VALIDATION_ID" \
      --network none \
      --cap-drop ALL \
      --security-opt no-new-privileges \
      --read-only \
      --pids-limit 256 \
      --memory 1g \
      --tmpfs /tmp:rw,noexec,nosuid,nodev,size=256m \
      --mount "type=bind,src=$MODULE,dst=/opt/atenea-playwright-module-v1,readonly" \
      --mount "type=bind,src=$CHECK,dst=/opt/atenea-check.js,readonly" \
      --mount "type=bind,src=$STATIC,dst=/work/static,readonly" \
      --mount "type=bind,src=$SLOT_ARTIFACT,dst=/artifacts" \
      -e NODE_PATH=/opt/atenea-playwright-module-v1/node_modules \
      "$IMAGE" node /opt/atenea-check.js
RESULT=$?
set -e
if [[ "$RESULT" -ne 0 ]]; then
  if docker_slot container inspect "$NAME" >/dev/null 2>&1; then
    LABELS="$(docker_slot container inspect --format \
      '{{index .Config.Labels "com.atenea.validation"}} {{index .Config.Labels "com.atenea.session-id"}} {{index .Config.Labels "com.atenea.validation-id"}}' \
      "$NAME")"
    if [[ "$LABELS" == "playwright-v1 $SESSION_ID $VALIDATION_ID" ]]; then
      docker_slot rm --force "$NAME" >/dev/null
    else
      printf 'foreign browser container retained\n' >&2
    fi
  fi
  exit "$RESULT"
fi
docker_slot container inspect "$NAME" >/dev/null 2>&1 && fail
jq -e '.schemaVersion == 1 and .valuesExposed == false
  and (.viewports | length == 2)
  and ([.viewports[] | .horizontalOverflow == false and .criticalVisible == true] | all)' \
  "$SLOT_ARTIFACT/report.json" >/dev/null || fail
install -o root -g root -m 0640 \
  "$SLOT_ARTIFACT/desktop.png" \
  "$SLOT_ARTIFACT/mobile.png" \
  "$SLOT_ARTIFACT/report.json" \
  "$ARTIFACT_ROOT/"
printf 'Playwright data, DOM and visual acceptance passed report_sha256=%s\n' \
  "$(sha256sum "$ARTIFACT_ROOT/report.json" | cut -d' ' -f1)"
