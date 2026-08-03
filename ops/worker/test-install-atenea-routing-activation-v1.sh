#!/usr/bin/env bash

set -Eeuo pipefail
umask 0077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="${SCRIPT_DIR}"
TEST_ROOT="$(mktemp -d /tmp/atenea-routing-install.XXXXXX)"
STAT_BIN="$(command -v stat)"

cleanup() {
  case "${TEST_ROOT}" in
    /tmp/atenea-routing-install.*) rm -rf -- "${TEST_ROOT}" ;;
  esac
}
trap cleanup EXIT

fail_test() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

source "${SOURCE_DIR}/install-atenea-routing-activation-v1.sh"
SCRIPT_DIR="${SOURCE_DIR}"
PROGRAM="${TEST_ROOT}/usr/local/libexec/atenea/atenea-workspace-activation-v1.sh"
SUDOERS="${TEST_ROOT}/etc/sudoers.d/92-atenea-routing-activation-v1"
WORKER_BUNDLE="${TEST_ROOT}/srv/atenea/worker/workspace-v1/ops/worker"

# The production verifier requires root-owned paths. This focused sandbox
# preserves and checks the real modes/hashes while projecting only the expected
# owners; AX42 verification covers the real owner values.
stat() {
  if [[ "$#" -eq 3 && "$1" == -c && "$2" == %U:%G:%a ]]; then
    local mode
    mode="$("${STAT_BIN}" -c %a "$3")"
    case "$3" in
      "${PROGRAM}"|"${SUDOERS}") printf 'root:root:%s\n' "${mode}" ;;
      "${WORKER_BUNDLE}"/*) printf 'atenea-worker:atenea:%s\n' "${mode}" ;;
      *) return 1 ;;
    esac
    return 0
  fi
  "${STAT_BIN}" "$@"
}

bundle_create() {
  mkdir -p "$(dirname -- "${PROGRAM}")" "$(dirname -- "${SUDOERS}")" \
    "${WORKER_BUNDLE}"
  cp "${SOURCE_DIR}/atenea-workspace-activation-v1.sh" "${PROGRAM}"
  chmod 0755 "${PROGRAM}"
  sudoers_content >"${SUDOERS}"
  chmod 0440 "${SUDOERS}"
  local dependency
  for dependency in "${DEPENDENCIES[@]}"; do
    cp "${SOURCE_DIR}/${dependency}" "${WORKER_BUNDLE}/${dependency}"
    chmod 0750 "${WORKER_BUNDLE}/${dependency}"
  done
}

bundle_reset() {
  rm -rf -- "${TEST_ROOT}/usr" "${TEST_ROOT}/etc" "${TEST_ROOT}/srv"
}

[[ "$(activation_bundle_preflight)" == absent ]] \
  || fail_test 'all-absent bundle was not accepted'

mkdir -p "$(dirname -- "${PROGRAM}")"
cp "${SOURCE_DIR}/atenea-workspace-activation-v1.sh" "${PROGRAM}"
chmod 0755 "${PROGRAM}"
if ( activation_bundle_preflight ) >/dev/null 2>&1; then
  fail_test 'partial activation bundle was accepted'
fi

bundle_reset
bundle_create
[[ "$(activation_bundle_preflight)" == current ]] \
  || fail_test 'exact current activation bundle was not accepted'

printf 'reviewed predecessor fixture\n' >"${PROGRAM}"
chmod 0755 "${PROGRAM}"
PROGRAM_PREDECESSOR_SHA256="$(sha256sum "${PROGRAM}" | cut -d' ' -f1)"
[[ "$(activation_bundle_preflight)" == predecessor ]] \
  || fail_test 'exact predecessor activation bundle was not accepted'

printf 'foreign activation fixture\n' >"${PROGRAM}"
chmod 0755 "${PROGRAM}"
PROGRAM_PREDECESSOR_SHA256="$(printf '0%.0s' {1..64})"
before="$(find "${TEST_ROOT}" -type f -print0 | sort -z | xargs -0 sha256sum)"
if ( activation_bundle_preflight ) >/dev/null 2>&1; then
  fail_test 'foreign activation program was accepted'
fi
after="$(find "${TEST_ROOT}" -type f -print0 | sort -z | xargs -0 sha256sum)"
[[ "${before}" == "${after}" ]] || fail_test 'rejected bundle was modified'

bundle_reset
bundle_create
mv "${PROGRAM}" "${PROGRAM}.target"
ln -s "${PROGRAM}.target" "${PROGRAM}"
if ( activation_bundle_preflight ) >/dev/null 2>&1; then
  fail_test 'symlinked activation program was accepted'
fi

bundle_reset
bundle_create
printf 'foreign dependency\n' >"${WORKER_BUNDLE}/${DEPENDENCIES[1]}"
chmod 0750 "${WORKER_BUNDLE}/${DEPENDENCIES[1]}"
if ( activation_bundle_preflight ) >/dev/null 2>&1; then
  fail_test 'foreign activation dependency was accepted'
fi

[[ "$(grep -Fc 'activation_bundle_preflight)' \
  "${SOURCE_DIR}/install-atenea-routing-activation-v1.sh")" -eq 2 ]] \
  || fail_test 'apply does not repeat the whole-bundle preflight before writing'

printf 'Atenea routing activation installer preflight tests passed\n'
