#!/usr/bin/env bash
set -euo pipefail
umask 0077

ACTION="${1:-}"
SESSION_ID="${2:-}"
CHANGE_ID="${3:-}"
CODE_COMMIT="${4:-}"
TEST_MODE="${ATENEA_MULTI_REPO_TEST_MODE:-0}"
UUID_RE='^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'

fail() {
  if [[ "${TEST_MODE:-0}" == 1 ]]; then
    printf 'test_rejection_line=%s\n' "${BASH_LINENO[0]:-unknown}" >&2
  fi
  printf 'MULTI_REPOSITORY_OWNERSHIP_REJECTED\n' >&2
  exit 65
}

[[ "$ACTION" == ensure && "$#" -eq 4 ]] || fail
[[ "$SESSION_ID" =~ $UUID_RE && "$CHANGE_ID" =~ $UUID_RE ]] || fail
[[ "$CODE_COMMIT" =~ ^[0-9a-f]{40}$ ]] || fail

if [[ "$TEST_MODE" == 1 ]]; then
  ROOT="${ATENEA_MULTI_REPO_TEST_ROOT:-}"
  [[ "$ROOT" == /tmp/* && -d "$ROOT" && ! -L "$ROOT" ]] || fail
  MIRROR="$ROOT/atenea.git"
  SESSION_ROOT="$ROOT/sessions/$SESSION_ID"
  CONFIG="$ROOT/project.json"
  WORKER_USER="$(id -un)"
  PROGRAM_USER="$WORKER_USER"
  SOURCE_USER="$WORKER_USER"
  run_worker() { "$@"; }
else
  [[ "$(id -u)" -eq 0 ]] || fail
  [[ -z "${SUDO_USER:-}" || "$SUDO_USER" == atenea-worker ]] || fail
  MIRROR=/srv/atenea/repositories/atenea.git
  SESSION_ROOT="/srv/atenea/workspaces/sessions/$SESSION_ID"
  CONFIG=/etc/atenea-worker/project-codex-v1.json
  WORKER_USER=atenea-worker
  PROGRAM_USER=atenea-program-role
  SOURCE_USER=atenea-worker-role
  run_worker() { runuser -u "$WORKER_USER" -- "$@"; }
fi

CODE_WORKTREE="$SESSION_ROOT/atenea"
PROGRAM_WORKTREE="$SESSION_ROOT/programme-openspec"
SOURCE_WORKTREE="$SESSION_ROOT/worker-source"
RECORD="$SESSION_ROOT/repository-roles-v1.json"
REPOSITORY=https://github.com/jlnieto/atenea.git
CODE_BRANCH=feature/actualizar-conversacion-en-web
PROGRAM_BRANCH=program/remote-codex-worker-platform
WORKSPACE_ID="remote:ax42-01:work-session:$SESSION_ID"

[[ -d "$MIRROR" && ! -L "$MIRROR" && -d "$CODE_WORKTREE" && ! -L "$CODE_WORKTREE" ]] || fail
[[ -f "$CONFIG" && ! -L "$CONFIG" ]] || fail
jq -e --arg identity "$WORKSPACE_ID" --arg session "$SESSION_ID" --arg commit "$CODE_COMMIT" \
  '.workspaces[$identity].sessionId == $session
   and .workspaces[$identity].canonicalCommit == $commit
   and .commit == $commit' "$CONFIG" >/dev/null || fail
[[ "$(git --git-dir="$MIRROR" config --get remote.origin.url)" == "$REPOSITORY" ]] || fail

if [[ "$TEST_MODE" != 1 ]]; then
  timeout 120s run_worker git --git-dir="$MIRROR" fetch --prune origin || fail
fi
MIRROR_CODE="$(git --git-dir="$MIRROR" rev-parse --verify "refs/remotes/origin/$CODE_BRANCH^{commit}")" || fail
PROGRAM_COMMIT="$(git --git-dir="$MIRROR" rev-parse --verify "refs/remotes/origin/$PROGRAM_BRANCH^{commit}")" || fail
[[ "$MIRROR_CODE" == "$CODE_COMMIT" && "$PROGRAM_COMMIT" =~ ^[0-9a-f]{40}$ ]] || fail
[[ "$(git -c safe.directory="$CODE_WORKTREE" -C "$CODE_WORKTREE" rev-parse HEAD)" == "$CODE_COMMIT" ]] || fail
if [[ -e "$RECORD" || -L "$RECORD" ]]; then
  [[ -f "$RECORD" && ! -L "$RECORD" ]] || fail
  jq -e --arg session "$SESSION_ID" --arg workspace "$WORKSPACE_ID" \
    --arg change "$CHANGE_ID" \
    '.sessionId == $session and .workspaceIdentity == $workspace
     and .changeIdentity == $change and .valuesExposed == false' \
    "$RECORD" >/dev/null || fail
fi

ensure_role_worktree() {
  local role="$1" path="$2" branch="$3" owner="$4"
  if [[ -f "$RECORD" ]]; then
    jq -e --arg role "$role" --arg branch "$branch" \
      --arg commit "$PROGRAM_COMMIT" \
      '.roles[$role].workspaceBranch == $branch and .roles[$role].commit == $commit' \
      "$RECORD" >/dev/null || fail
  elif [[ -e "$path" || -L "$path" ]]; then
    fail
  else
    run_worker git --git-dir="$MIRROR" worktree add --detach "$path" "$PROGRAM_COMMIT" >/dev/null || fail
    run_worker git -C "$path" switch -c "$branch" >/dev/null || fail
  fi
  [[ -d "$path" && ! -L "$path"
      && "$(git -c safe.directory="$path" -C "$path" rev-parse HEAD)" == "$PROGRAM_COMMIT" ]] || fail
  if [[ "$TEST_MODE" != 1 ]]; then
    chown -R "$owner:$owner" "$path"
    chmod -R go-rwx "$path"
    [[ "$(stat -c '%U:%G' "$path")" == "$owner:$owner" ]] || fail
    [[ "$((8#$(stat -c '%a' "$path") & 8#077))" -eq 0 ]] || fail
    find "$path" -xdev \( ! -user "$owner" -o ! -group "$owner" -o -perm /077 \) \
      -print -quit | grep -q . && fail
  fi
}

PROGRAM_WORKSPACE_BRANCH="atenea/program-$SESSION_ID"
SOURCE_WORKSPACE_BRANCH="atenea/worker-$SESSION_ID"
ensure_role_worktree PROGRAMME_OPENSPEC "$PROGRAM_WORKTREE" "$PROGRAM_WORKSPACE_BRANCH" "$PROGRAM_USER"
ensure_role_worktree WORKER_SOURCE "$SOURCE_WORKTREE" "$SOURCE_WORKSPACE_BRANCH" "$SOURCE_USER"

mirror_sha="$(printf '%s\0%s\0%s' "$REPOSITORY" "$MIRROR_CODE" "$PROGRAM_COMMIT" | sha256sum | cut -d' ' -f1)"
code_tree_sha="$(printf '%s\0%s\0%s' ATENEA_CODE "$CODE_COMMIT" "$WORKSPACE_ID" | sha256sum | cut -d' ' -f1)"
program_tree_sha="$(printf '%s\0%s\0%s' PROGRAMME_OPENSPEC "$PROGRAM_COMMIT" "$CHANGE_ID" | sha256sum | cut -d' ' -f1)"
worker_tree_sha="$(printf '%s\0%s\0%s' WORKER_SOURCE "$PROGRAM_COMMIT" "$CHANGE_ID" | sha256sum | cut -d' ' -f1)"

temporary="$(mktemp "$SESSION_ROOT/.repository-roles-v1.XXXXXX")"
jq -n --arg session "$SESSION_ID" --arg workspace "$WORKSPACE_ID" \
  --arg change "$CHANGE_ID" --arg repository "$REPOSITORY" \
  --arg codeCommit "$CODE_COMMIT" --arg programCommit "$PROGRAM_COMMIT" \
  --arg mirror "$mirror_sha" --arg codeTree "$code_tree_sha" \
  --arg programTree "$program_tree_sha" --arg workerTree "$worker_tree_sha" \
  --arg programBranch "$PROGRAM_WORKSPACE_BRANCH" --arg workerBranch "$SOURCE_WORKSPACE_BRANCH" '{
    schemaVersion: 1, sessionId: $session, workspaceIdentity: $workspace,
    changeIdentity: $change,
    roles: {
      ATENEA_CODE: {authority:"READ_WRITE", repository:$repository,
        branch:"feature/actualizar-conversacion-en-web", commit:$codeCommit,
        mirrorIdentitySha256:$mirror, worktreeIdentitySha256:$codeTree,
        validationProfile:"atenea-code-v1", readiness:"DRAFT"},
      PROGRAMME_OPENSPEC: {authority:"READ_WRITE", repository:$repository,
        branch:"program/remote-codex-worker-platform", workspaceBranch:$programBranch,
        commit:$programCommit, mirrorIdentitySha256:$mirror,
        worktreeIdentitySha256:$programTree,
        validationProfile:"openspec-strict-v1", readiness:"DRAFT"},
      WORKER_SOURCE: {authority:"READ_WRITE", repository:$repository,
        branch:"program/remote-codex-worker-platform", workspaceBranch:$workerBranch,
        commit:$programCommit, mirrorIdentitySha256:$mirror,
        worktreeIdentitySha256:$workerTree,
        validationProfile:"worker-contract-v1", readiness:"DRAFT"}
    }, valuesExposed:false
  }' >"$temporary"
chmod 0640 "$temporary"
mv -f "$temporary" "$RECORD"

jq -c '{
  sessionId, workspaceIdentity, changeIdentity,
  roles: [.roles | to_entries[] | {
    role:.key, authority:.value.authority, repository:.value.repository,
    branch:.value.branch, commit:.value.commit,
    mirrorIdentitySha256:.value.mirrorIdentitySha256,
    worktreeIdentitySha256:.value.worktreeIdentitySha256,
    validationProfile:.value.validationProfile, readiness:.value.readiness
  }], valuesExposed:false
}' "$RECORD"
