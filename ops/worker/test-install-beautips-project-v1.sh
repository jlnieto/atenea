#!/usr/bin/env bash

set -Eeuo pipefail
umask 0077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
INSTALLER="${SCRIPT_DIR}/install-beautips-project-v1.sh"
TEST_ROOT="$(mktemp -d /tmp/beautips-project-install.XXXXXX)"

cleanup() {
  case "${TEST_ROOT}" in
    /tmp/beautips-project-install.*)
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

run_installer() {
  ATENEA_BEAUTIPS_INSTALL_TEST_MODE=1 \
  ATENEA_BEAUTIPS_INSTALL_TEST_ROOT="${TEST_ROOT}" \
  ATENEA_BEAUTIPS_INSTALL_TEST_COMMIT="${TEST_COMMIT:-}" \
  ATENEA_BEAUTIPS_INSTALL_TEST_MANIFEST_SHA256="${TEST_MANIFEST_SHA256:-}" \
    "${INSTALLER}" "$@"
}

mkdir -p "${TEST_ROOT}/usr/local/libexec/atenea"
cp "${SCRIPT_DIR}/project-codex-runner-v1.py" \
  "${TEST_ROOT}/usr/local/libexec/atenea/project-codex-runner-v1.py"
chmod 0755 "${TEST_ROOT}/usr/local/libexec/atenea/project-codex-runner-v1.py"

run_installer plan >"${TEST_ROOT}/plan.json"
jq -e '
  .defaultState == {
    selectionEnabled: false,
    executionEnabled: false,
    workspaceCount: 0
  } and
  .publicListenerChanges == false and
  .firewallChanges == false and
  .serviceRestarts == false
' "${TEST_ROOT}/plan.json" >/dev/null || fail 'install plan is not closed'

run_installer apply >"${TEST_ROOT}/apply.json"
run_installer verify >"${TEST_ROOT}/verify.json"
ATENEA_BEAUTIPS_INSTALL_TEST_MODE=1 \
ATENEA_BEAUTIPS_INSTALL_TEST_ROOT="${TEST_ROOT}" \
  "${TEST_ROOT}/usr/local/libexec/atenea/install-beautips-project-v1.sh" verify \
  >"${TEST_ROOT}/installed-verify.json"
jq -e '
  .state == "verified" and
  .selectionEnabled == false and
  .executionEnabled == false and
  .workspaceCount == 0 and
  .publicListenerChanges == false
' "${TEST_ROOT}/verify.json" >/dev/null || fail 'default installed state differs'

first_hashes="$(
  find "${TEST_ROOT}/usr/local/libexec/atenea" "${TEST_ROOT}/etc" \
    -type f -print0 | sort -z | xargs -0 sha256sum
)"
run_installer apply >/dev/null
second_hashes="$(
  find "${TEST_ROOT}/usr/local/libexec/atenea" "${TEST_ROOT}/etc" \
    -type f -print0 | sort -z | xargs -0 sha256sum
)"
[[ "${first_hashes}" == "${second_hashes}" ]] ||
  fail 'repeated install was not byte-idempotent'

run_installer selection-enable >"${TEST_ROOT}/selection.json"
jq -e '
  .selectionEnabled == true and .executionEnabled == false and
  .workspaceCount == 0
' "${TEST_ROOT}/selection.json" >/dev/null ||
  fail 'selection enable changed execution or workspace state'
if run_installer enable >"${TEST_ROOT}/enable.stdout" 2>"${TEST_ROOT}/enable.stderr"; then
  fail 'execution enabled without one persisted workspace'
fi
grep -Fq 'requires selection and one exact persisted workspace' \
  "${TEST_ROOT}/enable.stderr" || fail 'execution denial was not actionable'

SESSION_ID='123e4567-e89b-42d3-a456-426614174000'
WORKSPACE_IDENTITY="remote:ax42-01:work-session:${SESSION_ID}"
SESSION_ROOT="${TEST_ROOT}/srv/atenea/workspaces/sessions/${SESSION_ID}"
WORKTREE="${SESSION_ROOT}/beautips"
MIRROR="${TEST_ROOT}/srv/atenea/repositories/beautips.git"
FIXTURE="${TEST_ROOT}/fixture-source"
mkdir -p "${FIXTURE}/ops" "$(dirname "${MIRROR}")" "${SESSION_ROOT}"
git -C "${FIXTURE}" init -q -b main
git -C "${FIXTURE}" config user.name 'Beautips lifecycle test'
git -C "${FIXTURE}" config user.email 'beautips-lifecycle@atenea.invalid'
printf 'synthetic manifest fixture\n' >"${FIXTURE}/ops/atenea-runtime.json"
git -C "${FIXTURE}" add ops/atenea-runtime.json
git -C "${FIXTURE}" commit -q -m fixture
TEST_COMMIT="$(git -C "${FIXTURE}" rev-parse HEAD)"
TEST_MANIFEST_SHA256="$(sha256sum "${FIXTURE}/ops/atenea-runtime.json" | cut -d' ' -f1)"
git clone -q --bare "${FIXTURE}" "${MIRROR}"
git --git-dir="${MIRROR}" remote set-url origin \
  'https://github.com/jlnieto/beautips.git'
git --git-dir="${MIRROR}" worktree add -q \
  -b "atenea/session-${SESSION_ID}" "${WORKTREE}" "${TEST_COMMIT}"
jq -n \
  --arg session "${SESSION_ID}" \
  --arg mirror "${MIRROR}" \
  --arg worktree "${WORKTREE}" \
  --arg commit "${TEST_COMMIT}" '{
    schemaVersion: 1,
    sessionId: $session,
    projectId: "beautips",
    canonicalRemote: "https://github.com/jlnieto/beautips.git",
    baseBranch: "main",
    branch: ("atenea/session-" + $session),
    mirrorPath: $mirror,
    worktreePath: $worktree,
    workerHost: "test-worker",
    state: "ready",
    expectedBaseCommit: $commit,
    headCommit: $commit
  }' >"${SESSION_ROOT}/workspace-v1.json"
jq -n \
  --arg session "${SESSION_ID}" \
  --arg mirror "${MIRROR}" \
  --arg worktree "${WORKTREE}" '{
    schemaVersion: 1,
    sessionId: $session,
    projectId: "beautips",
    branch: ("atenea/session-" + $session),
    mirrorPath: $mirror,
    worktreePath: $worktree,
    runtimeId: ("ws-" + ($session | gsub("-"; ""))),
    manifestRelativePath: "ops/atenea-runtime.json",
    slot: "slot4",
    workloadClass: "normal",
    state: "allocated",
    allocatedPorts: [
      {name: "postgres", internalPort: 5432, protocol: "tcp",
       bindAddress: "127.0.0.1", loopbackPort: 21001},
      {name: "redis", internalPort: 6379, protocol: "tcp",
       bindAddress: "127.0.0.1", loopbackPort: 21002},
      {name: "web", internalPort: 8080, protocol: "http",
       bindAddress: "127.0.0.1", loopbackPort: 21003}
    ]
  }' >"${SESSION_ROOT}/runtime-allocation-v1.json"

run_installer register "${SESSION_ID}" "${WORKSPACE_IDENTITY}" \
  >"${TEST_ROOT}/register.json"
jq -e \
  --arg identity "${WORKSPACE_IDENTITY}" \
  --arg session "${SESSION_ID}" '
    .selectionEnabled == true and .executionEnabled == false and
    (.workspaces | keys) == [$identity] and
    .workspaces[$identity].sessionId == $session
  ' "${TEST_ROOT}/etc/atenea-worker/beautips-project-codex-v1.json" >/dev/null ||
  fail 'exact workspace registration was not persisted'
run_installer enable >"${TEST_ROOT}/enabled.json"
jq -e '
  .selectionEnabled == true and .executionEnabled == true and
  .workspaceCount == 1
' "${TEST_ROOT}/enabled.json" >/dev/null ||
  fail 'exact registered workspace did not enable'
run_installer disable >/dev/null
if run_installer unregister "${SESSION_ID}" \
    'remote:ax42-01:work-session:223e4567-e89b-42d3-a456-426614174000' \
    >"${TEST_ROOT}/foreign-unregister.stdout" \
    2>"${TEST_ROOT}/foreign-unregister.stderr"; then
  fail 'foreign workspace unregister was accepted'
fi
run_installer unregister "${SESSION_ID}" "${WORKSPACE_IDENTITY}" \
  >"${TEST_ROOT}/unregister.json"
jq -e '
  .selectionEnabled == false and .executionEnabled == false and
  .workspaceCount == 0
' "${TEST_ROOT}/unregister.json" >/dev/null ||
  fail 'exact workspace unregister did not return to disabled-empty'

mediator="${TEST_ROOT}/usr/local/libexec/atenea/beautips-operation-mediator-v1.py"
cp "${mediator}" "${TEST_ROOT}/mediator.accepted"
printf '\\nforeign\\n' >>"${mediator}"
if run_installer verify >"${TEST_ROOT}/tamper.stdout" 2>"${TEST_ROOT}/tamper.stderr"; then
  fail 'verify accepted a modified installed mediator'
fi
if run_installer rollback >"${TEST_ROOT}/rollback-tamper.stdout" \
    2>"${TEST_ROOT}/rollback-tamper.stderr"; then
  fail 'rollback deleted a modified installed mediator'
fi
cp "${TEST_ROOT}/mediator.accepted" "${mediator}"
chmod 0755 "${mediator}"
run_installer verify >/dev/null
run_installer rollback >"${TEST_ROOT}/rollback.stdout"

for path in \
  "${TEST_ROOT}/usr/local/libexec/atenea/beautips-operation-mediator-v1.py" \
  "${TEST_ROOT}/usr/local/libexec/atenea/beautips-project-codex-runner-v1.py" \
  "${TEST_ROOT}/usr/local/libexec/atenea/beautips-secret-boundary-v1.py" \
  "${TEST_ROOT}/usr/local/libexec/atenea/beautips-runtime-operations-v1.json" \
  "${TEST_ROOT}/usr/local/libexec/atenea/project-codex-allowlist-v1.json" \
  "${TEST_ROOT}/usr/local/libexec/atenea/install-beautips-project-v1.sh" \
  "${TEST_ROOT}/etc/atenea-worker/beautips-project-codex-v1.json" \
  "${TEST_ROOT}/etc/sudoers.d/92-atenea-beautips-project-v1"; do
  [[ ! -e "${path}" && ! -L "${path}" ]] ||
    fail "rollback retained an owned install artifact: ${path}"
done
[[ -f "${TEST_ROOT}/usr/local/libexec/atenea/project-codex-runner-v1.py" ]] ||
  fail 'rollback removed the accepted shared base runner'

run_installer apply >/dev/null
run_installer disable >/dev/null
run_installer rollback >/dev/null

if rg -n 'ufw|firewall-cmd|iptables|nft|systemctl|tailscale|listen[(]' \
    "${INSTALLER}" >/dev/null; then
  fail 'installer contains listener, firewall or service mutation'
fi

printf 'Beautips project install lifecycle tests passed.\n'
