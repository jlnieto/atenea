#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MEDIATOR="$SCRIPT_DIR/atenea-multi-repository-v1.sh"
ROOT="$(mktemp -d /tmp/atenea-multi-repository-test.XXXXXX)"
SESSION=11111111-1111-4111-8111-111111111111
CHANGE=22222222-2222-4222-8222-222222222222
cleanup() {
  case "$ROOT" in
    /tmp/atenea-multi-repository-test.*) rm -rf -- "$ROOT" ;;
  esac
}
trap cleanup EXIT

mkdir -p "$ROOT/source" "$ROOT/sessions/$SESSION"
git -C "$ROOT/source" init -q -b feature/actualizar-conversacion-en-web
git -C "$ROOT/source" config user.name Test
git -C "$ROOT/source" config user.email test@example.invalid
printf 'code\n' >"$ROOT/source/code.txt"
git -C "$ROOT/source" add code.txt
git -C "$ROOT/source" commit -qm code
CODE="$(git -C "$ROOT/source" rev-parse HEAD)"
git -C "$ROOT/source" switch -qc program/remote-codex-worker-platform
mkdir "$ROOT/source/openspec" "$ROOT/source/ops"
printf 'programme\n' >"$ROOT/source/openspec/tasks.md"
printf 'worker\n' >"$ROOT/source/ops/worker.py"
git -C "$ROOT/source" add openspec ops
git -C "$ROOT/source" commit -qm program
PROGRAM="$(git -C "$ROOT/source" rev-parse HEAD)"
git clone -q --bare "$ROOT/source" "$ROOT/atenea.git"
git --git-dir="$ROOT/atenea.git" config remote.origin.url \
  https://github.com/jlnieto/atenea.git
git --git-dir="$ROOT/atenea.git" update-ref \
  refs/remotes/origin/feature/actualizar-conversacion-en-web "$CODE"
git --git-dir="$ROOT/atenea.git" update-ref \
  refs/remotes/origin/program/remote-codex-worker-platform "$PROGRAM"
git --git-dir="$ROOT/atenea.git" worktree add --detach \
  "$ROOT/sessions/$SESSION/atenea" "$CODE" >/dev/null
IDENTITY="remote:ax42-01:work-session:$SESSION"
jq -n --arg identity "$IDENTITY" --arg session "$SESSION" --arg commit "$CODE" '{
  commit:$commit, workspaces:{($identity):{
    sessionId:$session, canonicalCommit:$commit
  }}
}' >"$ROOT/project.json"

run() {
  ATENEA_MULTI_REPO_TEST_MODE=1 ATENEA_MULTI_REPO_TEST_ROOT="$ROOT" \
    "$MEDIATOR" ensure "$SESSION" "$CHANGE" "$CODE"
}
first="$(run)"
record_sha="$(sha256sum "$ROOT/sessions/$SESSION/repository-roles-v1.json" | cut -d' ' -f1)"
second="$(run)"
[[ "$first" == "$second" ]]
[[ "$record_sha" == "$(sha256sum "$ROOT/sessions/$SESSION/repository-roles-v1.json" | cut -d' ' -f1)" ]]
jq -e '.valuesExposed == false and (.roles | length == 3)
  and ([.roles[].role] | sort) ==
    ["ATENEA_CODE","PROGRAMME_OPENSPEC","WORKER_SOURCE"]
  and ([.roles[].authority == "READ_WRITE"] | all)
  and ([.roles[].readiness == "DRAFT"] | all)' <<<"$first" >/dev/null
[[ -d "$ROOT/sessions/$SESSION/atenea"
    && -d "$ROOT/sessions/$SESSION/programme-openspec"
    && -d "$ROOT/sessions/$SESSION/worker-source" ]]
code_path="$(realpath "$ROOT/sessions/$SESSION/atenea")"
program_path="$(realpath "$ROOT/sessions/$SESSION/programme-openspec")"
worker_path="$(realpath "$ROOT/sessions/$SESSION/worker-source")"
[[ "$code_path" != "$program_path" && "$program_path" != "$worker_path" ]]
grep -F 'PROGRAM_USER=atenea-program-role' "$MEDIATOR" >/dev/null
grep -F 'SOURCE_USER=atenea-worker-role' "$MEDIATOR" >/dev/null
grep -F 'chmod -R go-rwx "$path"' "$MEDIATOR" >/dev/null

if ATENEA_MULTI_REPO_TEST_MODE=1 ATENEA_MULTI_REPO_TEST_ROOT="$ROOT" \
    "$MEDIATOR" ensure "$SESSION" 33333333-3333-4333-8333-333333333333 "$CODE" \
    >/dev/null 2>&1; then
  printf 'different change identity was accepted\n' >&2
  exit 1
fi
[[ "$record_sha" == "$(sha256sum "$ROOT/sessions/$SESSION/repository-roles-v1.json" | cut -d' ' -f1)" ]]

if ATENEA_MULTI_REPO_TEST_MODE=1 ATENEA_MULTI_REPO_TEST_ROOT="$ROOT" \
    "$MEDIATOR" ensure "$SESSION" "$CHANGE" "$(printf f%.0s {1..40})" \
    >/dev/null 2>&1; then
  printf 'foreign code commit was accepted\n' >&2
  exit 1
fi
printf 'multi-repository synthetic ownership passed\n'
