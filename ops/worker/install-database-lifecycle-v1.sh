#!/usr/bin/env bash

set -Eeuo pipefail
umask 0077

ACTION="${1:-}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROGRAM="/usr/libexec/atenea-database-lifecycle-v1"
STATE_MODULE="/usr/libexec/database-lifecycle-state-v1.py"
CLIENT="/usr/local/bin/atenea-database-lifecycle-v1"
SUDOERS="/etc/sudoers.d/93-atenea-database-lifecycle-v1"
STATE_ROOT="/srv/atenea/worker/database-lifecycle-v1"
SNAPSHOT_ROOT="/srv/atenea/database-snapshots-v1"
ENABLED="/etc/atenea-worker/database-lifecycle-v1.enabled"

fail() {
  printf 'DATABASE_LIFECYCLE_INSTALL_FAILED: %s\n' "$1" >&2
  exit 1
}

[[ "${EUID}" -eq 0 ]] || fail "installer must run as root"

verify() {
  for path in "${PROGRAM}" "${STATE_MODULE}"; do
    [[ "$(stat -c %U:%G:%a "${path}")" == "root:root:755" ]] ||
      fail "installed mediator metadata differs: ${path}"
  done
  [[ "$(stat -c %U:%G:%a "${CLIENT}")" == "root:root:755" ]] ||
    fail "installed client metadata differs"
  [[ "$(stat -c %U:%G:%a "${STATE_ROOT}")" == "root:root:700" &&
      "$(stat -c %U:%G:%a "${SNAPSHOT_ROOT}")" == "root:root:700" ]] ||
    fail "private lifecycle roots differ"
  visudo -cf "${SUDOERS}" >/dev/null
  "${PROGRAM}" verify |
    jq -e '.protocolVersion == "database-lifecycle/v1" and
      .hostListenerRequired == false and .rootfulDockerAllowed == false' >/dev/null
  [[ ! -e /var/run/docker.sock ]] || fail "rootful Docker socket must remain absent"
  printf 'DATABASE_LIFECYCLE_V1_VERIFIED enabled=%s\n' \
    "$([[ -f "${ENABLED}" ]] && printf true || printf false)"
}

apply_install() {
  for command in install jq python3 stat visudo; do
    command -v "${command}" >/dev/null || fail "missing prerequisite: ${command}"
  done
  python3 -m py_compile \
    "${SCRIPT_DIR}/database-lifecycle-state-v1.py" \
    "${SCRIPT_DIR}/database-lifecycle-worker-v1.py"
  bash -n "${SCRIPT_DIR}/database-lifecycle-client-v1.sh"
  install -d -o root -g root -m 0755 /usr/libexec /usr/local/bin
  install -d -o root -g atenea -m 0750 /etc/atenea-worker
  install -d -o root -g root -m 0700 "${STATE_ROOT}" "${SNAPSHOT_ROOT}"
  chmod 0700 "${STATE_ROOT}" "${SNAPSHOT_ROOT}"
  install -o root -g root -m 0755 \
    "${SCRIPT_DIR}/database-lifecycle-state-v1.py" "${STATE_MODULE}"
  install -o root -g root -m 0755 \
    "${SCRIPT_DIR}/database-lifecycle-worker-v1.py" "${PROGRAM}"
  install -o root -g root -m 0755 \
    "${SCRIPT_DIR}/database-lifecycle-client-v1.sh" "${CLIENT}"
  temporary="$(mktemp /etc/sudoers.d/.93-atenea-database-lifecycle-v1.XXXXXX)"
  printf '%s\n' \
    'atenea-worker ALL=(root) NOPASSWD: /usr/libexec/atenea-database-lifecycle-v1 *' \
    >"${temporary}"
  chmod 0440 "${temporary}"
  visudo -cf "${temporary}" >/dev/null
  mv "${temporary}" "${SUDOERS}"
  chown root:root "${SUDOERS}"
  chmod 0440 "${SUDOERS}"
  rm -f "${ENABLED}"
  verify
}

case "${ACTION}" in
  apply)
    apply_install
    ;;
  verify)
    verify
    ;;
  enable)
    install -o root -g atenea -m 0640 /dev/null "${ENABLED}"
    verify
    ;;
  disable)
    rm -f "${ENABLED}"
    verify
    ;;
  reconcile)
    "${PROGRAM}" reconcile
    ;;
  rollback)
    rm -f "${ENABLED}" "${SUDOERS}" "${CLIENT}" "${PROGRAM}" "${STATE_MODULE}"
    ;;
  *)
    fail "usage: $0 apply|verify|enable|disable|reconcile|rollback"
    ;;
esac
