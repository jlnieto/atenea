#!/usr/bin/env bash

set -Eeuo pipefail
umask 0077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TEST_ROOT="$(mktemp -d /tmp/agent-run-worker-install.XXXXXX)"

cleanup() {
  case "${TEST_ROOT}" in
    /tmp/agent-run-worker-install.*)
      chmod -R u+w "${TEST_ROOT}" 2>/dev/null || true
      rm -rf -- "${TEST_ROOT}"
      ;;
  esac
}
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

source "${SCRIPT_DIR}/install-agent-run-worker-v1.sh"
require_root() { :; }
chown() { :; }

[[ "$(sha256sum "${SCRIPT_DIR}/templates/atenea-agent-run-worker-v1.service" | cut -d' ' -f1)" \
    == "${SERVICE_TEMPLATE_SHA256}" ]] || fail "service template fingerprint is stale"
[[ "$(sha256sum "${SCRIPT_DIR}/codex-platform-instructions-v1.md" | cut -d' ' -f1)" \
    == "${PLATFORM_INSTRUCTIONS_SHA256}" ]] || fail "platform instruction fingerprint is stale"

SESSION_ID=11111111-1111-4111-8111-111111111111
WORKSPACE_IDENTITY="remote:ax42-01:work-session:${SESSION_ID}"
WORKTREE="${TEST_ROOT}/srv/atenea/workspaces/sessions/${SESSION_ID}/atenea"
PROJECT_MIRROR="${TEST_ROOT}/srv/atenea/repositories/atenea.git"
PROJECT_REF="refs/remotes/origin/${PROJECT_BRANCH}"
PROJECT_WORKSPACES_ROOT="${TEST_ROOT}/srv/atenea/workspaces/sessions"
PROJECT_CONFIG="${TEST_ROOT}/etc/atenea-worker/project-codex-v1.json"
mkdir -p "${WORKTREE}" "$(dirname -- "${PROJECT_MIRROR}")" "$(dirname -- "${PROJECT_CONFIG}")"

git init -q --bare "${PROJECT_MIRROR}"
git init -q -b "${PROJECT_BRANCH}" "${WORKTREE}"
git -C "${WORKTREE}" config user.name Test
git -C "${WORKTREE}" config user.email test@example.invalid
git -C "${WORKTREE}" remote add origin "${PROJECT_REPOSITORY}"
mkdir -p "${WORKTREE}/ops"
printf '{}\n' >"${WORKTREE}/ops/atenea-runtime.json"
PROJECT_MANIFEST_SHA256="$(sha256sum "${WORKTREE}/ops/atenea-runtime.json" | cut -d' ' -f1)"
printf 'base\n' >"${WORKTREE}/tracked.txt"
git -C "${WORKTREE}" add .
git -C "${WORKTREE}" commit -qm base
RETAINED_COMMIT="$(git -C "${WORKTREE}" rev-parse HEAD)"
git --git-dir="${PROJECT_MIRROR}" fetch -q "${WORKTREE}" \
  "${RETAINED_COMMIT}:refs/remotes/origin/${PROJECT_BRANCH}"
printf 'canonical\n' >>"${WORKTREE}/tracked.txt"
git -C "${WORKTREE}" add tracked.txt
git -C "${WORKTREE}" commit -qm canonical
CANONICAL_COMMIT="$(git -C "${WORKTREE}" rev-parse HEAD)"
git --git-dir="${PROJECT_MIRROR}" fetch -q "${WORKTREE}" \
  "+${CANONICAL_COMMIT}:refs/remotes/origin/${PROJECT_BRANCH}"
git -C "${WORKTREE}" reset -q "${RETAINED_COMMIT}"
printf 'draft\n' >>"${WORKTREE}/tracked.txt"
printf 'owned\n' >"${TEST_ROOT}/allocation"
mkdir -p "$(dirname -- "${WORKTREE}")"
cp "${TEST_ROOT}/allocation" "$(dirname -- "${WORKTREE}")/runtime-allocation-v1.json"

write_project_config false false '{}' "${CANONICAL_COMMIT}"
BEFORE="$(git -C "${WORKTREE}" status --porcelain=v1 --untracked-files=all)"
project_retained_draft_register "${SESSION_ID}" "${WORKSPACE_IDENTITY}" "${RETAINED_COMMIT}"
project_retained_draft_register "${SESSION_ID}" "${WORKSPACE_IDENTITY}" "${RETAINED_COMMIT}"
AFTER="$(git -C "${WORKTREE}" status --porcelain=v1 --untracked-files=all)"

[[ "${BEFORE}" == "${AFTER}" ]] || fail "retained draft changed"
jq -e \
  --arg identity "${WORKSPACE_IDENTITY}" \
  --arg retained "${RETAINED_COMMIT}" \
  --arg canonical "${CANONICAL_COMMIT}" \
  '.selectionEnabled == true and
   .executionEnabled == false and
   .commit == $canonical and
   (.workspaces | keys) == [$identity] and
   .workspaces[$identity].canonicalCommit == $retained' \
  "${PROJECT_CONFIG}" >/dev/null || fail "retained registration is not exact"

if ( project_retained_draft_register \
    "${SESSION_ID}" "${WORKSPACE_IDENTITY}" "${CANONICAL_COMMIT}" ) >/dev/null 2>&1; then
  fail "current commit was accepted as retained"
fi

printf 'agent-run worker retained-draft installer tests passed\n'
