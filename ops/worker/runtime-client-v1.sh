#!/usr/bin/env bash

set -Eeuo pipefail

TEST_MODE="${ATENEA_RUNTIME_CLIENT_TEST_MODE:-0}"
SERVICE_USER="atenea-worker"

fail() {
  local code="$1" message="$2" action="$3"
  printf '%s: %s\nNext action: %s\n' \
    "${code}" "${message}" "${action}" >&2
  exit 65
}

if [[ "${TEST_MODE}" == "1" ]]; then
  MANAGER="${ATENEA_RUNTIME_MANAGER:-}"
  [[ "${MANAGER}" == /tmp/* && "${MANAGER}" != *".."* &&
      -f "${MANAGER}" && ! -L "${MANAGER}" && -x "${MANAGER}" &&
      "$(stat -c %u "${MANAGER}")" == "$(id -u)" ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "Synthetic runtime manager is unsafe." \
      "Use an owned manager fixture beneath the test root."
  exec "${MANAGER}" "$@"
fi

[[ "$(id -un)" == "${SERVICE_USER}" ]] ||
  fail "RUNTIME_OWNERSHIP_CONFLICT" "Runtime client must run as ${SERVICE_USER}." \
    "Invoke dev through the worker service identity."
MANAGER="/usr/libexec/atenea-runtime-manager-v1"
[[ -f "${MANAGER}" && ! -L "${MANAGER}" && -x "${MANAGER}" &&
    "$(stat -c %u "${MANAGER}")" == "0" &&
    "$(stat -c %a "${MANAGER}")" =~ ^[57][0-5][0-5]$ ]] ||
  fail "TOOLCHAIN_UNAVAILABLE" "The root-owned runtime manager is unavailable." \
    "Install and verify task 4.2 before accepting lifecycle work."

exec /usr/bin/sudo -n "${MANAGER}" "$@"
