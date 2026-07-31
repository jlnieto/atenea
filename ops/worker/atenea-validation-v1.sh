#!/usr/bin/env bash
set -euo pipefail

OPERATION="${1:-}"
SESSION_ID="${2:-}"
SOURCE_SHA="${3:-}"
VALIDATION_ID="${4:-}"
CONFIG="/etc/atenea-worker/project-codex-v1.json"
ARTIFACT_ROOT="/srv/atenea/artifacts/validations"
RUN_ROOT=""
TMP=""

fail() {
  printf 'validation authority rejected\n' >&2
  exit 64
}

[[ "$#" -eq 4 && "$(id -u)" -eq 0 ]] || fail
[[ "$SESSION_ID" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] || fail
[[ "$VALIDATION_ID" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] || fail
[[ "$SOURCE_SHA" =~ ^[0-9a-f]{64}$ ]] || fail
[[ -f "$CONFIG" && ! -L "$CONFIG" && "$(stat -c '%U:%G:%a' "$CONFIG")" == "root:root:644" ]] || fail

WORKSPACE_ID="remote:ax42-01:work-session:${SESSION_ID}"
WORKTREE="/srv/atenea/workspaces/sessions/${SESSION_ID}/atenea"
[[ -d "$WORKTREE" && ! -L "$WORKTREE" ]] || fail
jq -e \
  --arg identity "$WORKSPACE_ID" \
  --arg session "$SESSION_ID" \
  --arg worktree "$WORKTREE" \
  '.schemaVersion == "project-codex-v1"
   and .projectId == "atenea"
   and .repository == "https://github.com/jlnieto/atenea.git"
   and .branch == "feature/actualizar-conversacion-en-web"
   and .workspaces[$identity].sessionId == $session
   and .workspaces[$identity].worktree == $worktree
   and .workspaces[$identity].canonicalCommit == .commit' \
  "$CONFIG" >/dev/null || fail

case "$OPERATION" in
  BACKEND_TEST)
    DEFINITION="atenea-backend-test-v1"
    LIMIT=900
    COMMAND=(./mvnw -q test)
    ;;
  WEB_BUILD)
    DEFINITION="atenea-web-build-v1"
    LIMIT=600
    COMMAND=(./scripts/web-build.sh)
    ;;
  ANDROID_BUILD)
    DEFINITION="atenea-android-build-v1"
    LIMIT=1200
    COMMAND=(
      env -i
      PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
      HOME=/tmp
      ATENEA_ANDROID_HOME_DIR=/tmp/atenea-validation-android-home
      ATENEA_APK_SECRET_FILE=/nonexistent
      ATENEA_ANDROID_FIREBASE_FILE=/nonexistent
      ./scripts/android-build.sh :app:assembleDebug
    )
    ;;
  *) fail ;;
esac

HEAD="$(git -c safe.directory="$WORKTREE" -C "$WORKTREE" rev-parse --verify 'HEAD^{commit}')" || fail
EXPECTED="$(jq -r '.commit' "$CONFIG")"
[[ "$HEAD" == "$EXPECTED" && "$HEAD" =~ ^[0-9a-f]{40}$ ]] || fail
BEFORE="$(git -c safe.directory="$WORKTREE" -C "$WORKTREE" status --porcelain=v2 -z --untracked-files=all | sha256sum | cut -d' ' -f1)"

install -d -o root -g root -m 0750 "$ARTIFACT_ROOT/$SESSION_ID"
RUN_ROOT="$(mktemp -d "$ARTIFACT_ROOT/$SESSION_ID/.validation-run.XXXXXX")"
TMP="$(mktemp "$ARTIFACT_ROOT/$SESSION_ID/.validation-output.XXXXXX")"
trap 'rm -f -- "$TMP"; [[ -n "$RUN_ROOT" ]] && rm -rf -- "$RUN_ROOT"' EXIT
mkdir "$RUN_ROOT/repo"
tar --exclude='./.git' -C "$WORKTREE" -cf - . | tar -C "$RUN_ROOT/repo" -xf -
START="$(date +%s%3N)"
set +e
(cd "$RUN_ROOT/repo" && timeout --signal=TERM --kill-after=15s "${LIMIT}s" "${COMMAND[@]}") >"$TMP" 2>&1
EXIT_CODE=$?
set -e
END="$(date +%s%3N)"
DURATION=$((END - START))
AFTER="$(git -c safe.directory="$WORKTREE" -C "$WORKTREE" status --porcelain=v2 -z --untracked-files=all | sha256sum | cut -d' ' -f1)"
[[ "$BEFORE" == "$AFTER" ]] || fail
OUTPUT_SHA="$(sha256sum "$TMP" | cut -d' ' -f1)"
MANIFEST_SHA="$(printf '%s\0%s\0%s\0%s\0%s\0%s' "$VALIDATION_ID" "$DEFINITION" "$SOURCE_SHA" "$EXIT_CODE" "$DURATION" "$OUTPUT_SHA" | sha256sum | cut -d' ' -f1)"

if [[ "$EXIT_CODE" -eq 0 ]]; then
  STATUS="SUCCEEDED"
  SUMMARY="Closed validation passed"
elif [[ "$EXIT_CODE" -eq 124 || "$EXIT_CODE" -eq 137 ]]; then
  STATUS="BLOCKED"
  SUMMARY="Closed validation exceeded its finite timeout"
  EXIT_CODE_JSON="null"
else
  STATUS="FAILED"
  SUMMARY="Closed validation failed"
fi
EXIT_CODE_JSON="${EXIT_CODE_JSON:-$EXIT_CODE}"
jq -n \
  --arg validationId "$VALIDATION_ID" \
  --arg sessionId "$SESSION_ID" \
  --arg operation "$OPERATION" \
  --arg definitionRevision "$DEFINITION" \
  --arg sourceTreeFingerprintSha256 "$SOURCE_SHA" \
  --arg status "$STATUS" \
  --argjson exitCode "$EXIT_CODE_JSON" \
  --argjson durationMillis "$DURATION" \
  --arg artifactManifestSha256 "$MANIFEST_SHA" \
  --arg summary "$SUMMARY" \
  '{
    validationId: $validationId,
    sessionId: $sessionId,
    operation: $operation,
    definitionRevision: $definitionRevision,
    sourceTreeFingerprintSha256: $sourceTreeFingerprintSha256,
    status: $status,
    exitCode: $exitCode,
    durationMillis: $durationMillis,
    artifactManifestSha256: $artifactManifestSha256,
    summary: $summary,
    valuesExposed: false
  }'
