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
run_installer disable >/dev/null

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
