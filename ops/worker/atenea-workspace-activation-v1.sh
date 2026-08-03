#!/usr/bin/env bash

set -Eeuo pipefail
umask 0077
export PYTHONDONTWRITEBYTECODE=1

ACTION="${1:-}"
SESSION_ID="${2:-}"
WORKSPACE_BRANCH="${3:-}"

fail() {
  printf 'ATENEA_WORKSPACE_ACTIVATION_REJECTED: %s\n' "$1" >&2
  exit 65
}

[[ "${ACTION}" == "ensure" && "$#" -eq 3 ]] ||
  fail 'usage: ensure SESSION_UUID WORKSPACE_BRANCH'
[[ "${SESSION_ID}" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] ||
  fail 'session identity is not a canonical UUID'
[[ "${WORKSPACE_BRANCH}" == "atenea/session-${SESSION_ID}" ]] ||
  fail 'workspace branch is not a persisted WorkSession branch'
[[ "${EUID}" -eq 0 ]] || fail 'activation mediator requires root'
[[ -z "${SUDO_USER:-}" || "${SUDO_USER}" == atenea-worker ]] ||
  fail 'activation caller is foreign'

TOOL_ROOT=/srv/atenea/worker/workspace-v1/ops/worker
PROJECT_ROOT=/usr/local/libexec/atenea
CONFIG=/etc/atenea-worker/project-codex-v1.json
WORKSPACE_TOOL="${TOOL_ROOT}/session-workspace-v1.sh"
ADMISSION_TOOL="${TOOL_ROOT}/runtime-admission-v1.sh"
ALLOCATION_TOOL="${TOOL_ROOT}/session-runtime-allocation-v1.sh"
PROJECT_TOOL="${PROJECT_ROOT}/install-agent-run-worker-v1.sh"
PREFIX_COMMAND=(runuser -u atenea-worker --)

for tool in \
  "${WORKSPACE_TOOL}" "${ADMISSION_TOOL}" "${ALLOCATION_TOOL}" "${PROJECT_TOOL}"; do
  [[ -f "${tool}" && ! -L "${tool}" && -x "${tool}" ]] ||
    fail "required reviewed tool is unavailable: $(basename -- "${tool}")"
done
[[ -f "${CONFIG}" && ! -L "${CONFIG}" ]] ||
  fail 'Atenea project configuration is unavailable'

PROJECT_ID=atenea
REPOSITORY=https://github.com/jlnieto/atenea.git
BASE_BRANCH=main
MIRROR=/srv/atenea/repositories/atenea.git
CANONICAL_REF="refs/remotes/origin/${BASE_BRANCH}"
PINNED_COMMIT="$(git --git-dir="${MIRROR}" rev-parse --verify "${CANONICAL_REF}^{commit}")" ||
  fail 'canonical mirror ref is unavailable'
[[ "${PINNED_COMMIT}" =~ ^[0-9a-f]{40}$ ]] ||
  fail 'canonical mirror ref is ambiguous'
MANIFEST_SHA256=327a0c521017109d7c0067a11e7d8c3ad2079de4ea78d28296848f9de39c164b
SLOT=slot2
WORKSPACE_IDENTITY="remote:ax42-01:work-session:${SESSION_ID}"

"${PREFIX_COMMAND[@]}" env \
  ATENEA_PINNED_BASE_COMMIT="${PINNED_COMMIT}" \
  "${WORKSPACE_TOOL}" ensure "${SESSION_ID}" "${PROJECT_ID}" "${REPOSITORY}" \
  "${BASE_BRANCH}" "${WORKSPACE_BRANCH}" >/dev/null

"${PREFIX_COMMAND[@]}" "${ADMISSION_TOOL}" --json acquire-normal \
  "${SESSION_ID}" "${SLOT}" >/dev/null
"${PREFIX_COMMAND[@]}" "${ADMISSION_TOOL}" --json acquire-heavy \
  "${SESSION_ID}" >/dev/null

WORKTREE="/srv/atenea/workspaces/sessions/${SESSION_ID}/atenea"
MANIFEST="${WORKTREE}/ops/atenea-runtime.json"
[[ -f "${MANIFEST}" && ! -L "${MANIFEST}" &&
    "$(sha256sum "${MANIFEST}" | cut -d' ' -f1)" == "${MANIFEST_SHA256}" ]] ||
  fail 'workspace manifest does not match the accepted identity'

"${PREFIX_COMMAND[@]}" "${ALLOCATION_TOOL}" ensure \
  "${SESSION_ID}" "${SLOT}" "${MANIFEST}" >/dev/null
"${PROJECT_TOOL}" project-activate "${SESSION_ID}" "${WORKSPACE_IDENTITY}" >/dev/null

jq -e \
  --arg session "${SESSION_ID}" \
  --arg identity "${WORKSPACE_IDENTITY}" \
  --arg commit "${PINNED_COMMIT}" \
  '(.selectionEnabled == true) and
   (.executionEnabled == true) and
   (.commit == $commit) and
   (.workspaces | keys) == [$identity] and
   .workspaces[$identity].sessionId == $session and
   .workspaces[$identity].canonicalCommit == $commit' \
  "${CONFIG}" >/dev/null ||
  fail 'final persisted Atenea registration is not exact'

jq -cn \
  --arg session "${SESSION_ID}" \
  --arg identity "${WORKSPACE_IDENTITY}" \
  --arg branch "${WORKSPACE_BRANCH}" \
  --arg slot "${SLOT}" \
  --arg commit "${PINNED_COMMIT}" '{
    state: "ready",
    sessionId: $session,
    workspaceIdentity: $identity,
    projectId: "atenea",
    workspaceBranch: $branch,
    slot: $slot,
    canonicalCommit: $commit,
    selectionEnabled: true,
    executionEnabled: true,
    valuesExposed: false
  }'
