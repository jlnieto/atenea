#!/usr/bin/env bash

set -Eeuo pipefail
umask 0077

ACTION="${1:-}"
SESSION_ID="${2:-}"
WORKSPACE_BRANCH="${3:-}"
TEST_MODE="${ATENEA_BEAUTIPS_ACTIVATION_TEST_MODE:-0}"

fail() {
  printf 'BEAUTIPS_WORKSPACE_ACTIVATION_REJECTED: %s\n' "$1" >&2
  exit 65
}

[[ "${ACTION}" == "ensure" && "$#" -eq 3 ]] ||
  fail 'usage: ensure SESSION_UUID WORKSPACE_BRANCH'
[[ "${SESSION_ID}" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] ||
  fail 'session identity is not a canonical UUID'
[[ "${WORKSPACE_BRANCH}" =~ ^codex/work-session-[1-9][0-9]*$ ]] ||
  fail 'workspace branch is not a persisted WorkSession branch'

if [[ "${TEST_MODE}" == 1 ]]; then
  [[ "${ATENEA_BEAUTIPS_ACTIVATION_TEST_ROOT:-}" == /tmp/* &&
      "${ATENEA_BEAUTIPS_ACTIVATION_TEST_ROOT}" != *'..'* ]] ||
    fail 'test root must be an explicit path beneath /tmp'
  LIBEXEC="${ATENEA_BEAUTIPS_ACTIVATION_TEST_ROOT}/usr/local/libexec/atenea"
  CONFIG="${ATENEA_BEAUTIPS_ACTIVATION_TEST_ROOT}/etc/atenea-worker/beautips-project-codex-v1.json"
  PREFIX_COMMAND=()
else
  [[ "${EUID}" -eq 0 ]] || fail 'activation mediator requires root'
  [[ -z "${SUDO_USER:-}" || "${SUDO_USER}" == atenea-worker ]] ||
    fail 'activation caller is foreign'
  LIBEXEC=/usr/local/libexec/atenea
  CONFIG=/etc/atenea-worker/beautips-project-codex-v1.json
  PREFIX_COMMAND=(runuser -u atenea-worker --)
fi

WORKSPACE_TOOL="${LIBEXEC}/session-workspace-v1.sh"
ADMISSION_TOOL="${LIBEXEC}/runtime-admission-v1.sh"
ALLOCATION_TOOL="${LIBEXEC}/session-runtime-allocation-v1.sh"
PROJECT_TOOL="${LIBEXEC}/install-beautips-project-v1.sh"
for tool in \
  "${WORKSPACE_TOOL}" "${ADMISSION_TOOL}" "${ALLOCATION_TOOL}" "${PROJECT_TOOL}"; do
  [[ -f "${tool}" && ! -L "${tool}" && -x "${tool}" ]] ||
    fail "required reviewed tool is unavailable: $(basename -- "${tool}")"
done
[[ -f "${CONFIG}" && ! -L "${CONFIG}" ]] ||
  fail 'Beautips project configuration is unavailable'

PROJECT_ID=beautips
REPOSITORY=https://github.com/jlnieto/beautips.git
BASE_BRANCH=main
PINNED_COMMIT=e9e0b3c319c518363d4135f5378ebbddced96dfb
MANIFEST_SHA256=365f1c66c51c9018c2c6f48deddbaa619b4588cae2dd463dcd916cde884e2e82
SLOT=slot4
WORKSPACE_IDENTITY="remote:ax42-01:work-session:${SESSION_ID}"

"${PREFIX_COMMAND[@]}" env ATENEA_PINNED_BASE_COMMIT="${PINNED_COMMIT}" \
  "${WORKSPACE_TOOL}" ensure "${SESSION_ID}" "${PROJECT_ID}" "${REPOSITORY}" \
  "${BASE_BRANCH}" "${WORKSPACE_BRANCH}" >/dev/null

if [[ "${TEST_MODE}" == 1 ]]; then
  "${ADMISSION_TOOL}" --json acquire-normal "${SESSION_ID}" "${SLOT}" >/dev/null
else
  "${PREFIX_COMMAND[@]}" "${ADMISSION_TOOL}" --json acquire-normal \
    "${SESSION_ID}" "${SLOT}" >/dev/null
fi

if [[ "${TEST_MODE}" == 1 ]]; then
  WORKTREE="${ATENEA_BEAUTIPS_ACTIVATION_TEST_ROOT}/srv/atenea/workspaces/sessions/${SESSION_ID}/beautips"
else
  WORKTREE="/srv/atenea/workspaces/sessions/${SESSION_ID}/beautips"
fi
MANIFEST="${WORKTREE}/ops/atenea-runtime.json"
[[ -f "${MANIFEST}" && ! -L "${MANIFEST}" &&
    "$(sha256sum "${MANIFEST}" | cut -d' ' -f1)" == "${MANIFEST_SHA256}" ]] ||
  fail 'workspace manifest does not match the accepted identity'

"${PREFIX_COMMAND[@]}" "${ALLOCATION_TOOL}" ensure \
  "${SESSION_ID}" "${SLOT}" "${MANIFEST}" >/dev/null
"${PROJECT_TOOL}" selection-enable >/dev/null
"${PROJECT_TOOL}" register "${SESSION_ID}" "${WORKSPACE_IDENTITY}" >/dev/null
"${PROJECT_TOOL}" enable >/dev/null

jq -e \
  --arg session "${SESSION_ID}" \
  --arg identity "${WORKSPACE_IDENTITY}" \
  '(.selectionEnabled == true) and
   (.executionEnabled == true) and
   (.workspaces | keys) == [$identity] and
   .workspaces[$identity].sessionId == $session' \
  "${CONFIG}" >/dev/null ||
  fail 'final persisted Beautips registration is not exact'

jq -cn \
  --arg session "${SESSION_ID}" \
  --arg identity "${WORKSPACE_IDENTITY}" \
  --arg branch "${WORKSPACE_BRANCH}" \
  --arg slot "${SLOT}" '{
    state: "ready",
    sessionId: $session,
    workspaceIdentity: $identity,
    projectId: "beautips",
    workspaceBranch: $branch,
    slot: $slot,
    selectionEnabled: true,
    executionEnabled: true,
    valuesExposed: false
  }'
