#!/usr/bin/env bash

set -Eeuo pipefail
umask 0077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="${SCRIPT_DIR}"
TEST_ROOT="$(mktemp -d /tmp/atenea-routing-install.XXXXXX)"
STAT_BIN="$(command -v stat)"
INSTALL_BIN="$(command -v install)"

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
RELEASE_PROGRAM="${TEST_ROOT}/usr/local/libexec/atenea/atenea-workspace-release-v1.py"
SUDOERS="${TEST_ROOT}/etc/sudoers.d/92-atenea-routing-activation-v1"
WORKER_BUNDLE="${TEST_ROOT}/srv/atenea/worker/workspace-v1/ops/worker"
RELEASE_STATE_ROOT="${TEST_ROOT}/srv/atenea/worker/workspace-release-v1/sessions"
require_root() { :; }
chown() { :; }
visudo() { :; }
install() {
  local arguments=()
  while [[ "$#" -gt 0 ]]; do
    case "$1" in
      -o|-g) shift 2 ;;
      *) arguments+=("$1"); shift ;;
    esac
  done
  "${INSTALL_BIN}" "${arguments[@]}"
}

# The production verifier requires root-owned paths. This focused sandbox
# preserves and checks the real modes/hashes while projecting only the expected
# owners; AX42 verification covers the real owner values.
stat() {
  if [[ "$#" -eq 3 && "$1" == -c && "$2" == %U:%G:%a ]]; then
    local mode
    mode="$("${STAT_BIN}" -c %a "$3")"
    case "$3" in
      "${PROGRAM}"|"${RELEASE_PROGRAM}"|"${SUDOERS}"|"${RELEASE_STATE_ROOT}")
        printf 'root:root:%s\n' "${mode}"
        ;;
      "${WORKER_BUNDLE}"/*) printf 'atenea-worker:atenea:%s\n' "${mode}" ;;
      *) return 1 ;;
    esac
    return 0
  fi
  "${STAT_BIN}" "$@"
}

bundle_common() {
  mkdir -p "$(dirname -- "${PROGRAM}")" "$(dirname -- "${SUDOERS}")" \
    "${WORKER_BUNDLE}"
  cp "${SOURCE_DIR}/atenea-workspace-activation-v1.sh" "${PROGRAM}"
  chmod 0755 "${PROGRAM}"
  local dependency
  for dependency in "${DEPENDENCIES[@]}"; do
    cp "${SOURCE_DIR}/${dependency}" "${WORKER_BUNDLE}/${dependency}"
    chmod 0750 "${WORKER_BUNDLE}/${dependency}"
  done
}

bundle_create_current() {
  bundle_common
  cp "${SOURCE_DIR}/atenea-workspace-release-v1.py" "${RELEASE_PROGRAM}"
  chmod 0755 "${RELEASE_PROGRAM}"
  mkdir -p "${RELEASE_STATE_ROOT}"
  chmod 0700 "${RELEASE_STATE_ROOT}"
  sudoers_content >"${SUDOERS}"
  chmod 0440 "${SUDOERS}"
}

bundle_create_predecessor() {
  bundle_common
  predecessor_sudoers_content >"${SUDOERS}"
  chmod 0440 "${SUDOERS}"
}

bundle_reset() {
  rm -rf -- "${TEST_ROOT}/usr" "${TEST_ROOT}/etc" "${TEST_ROOT}/srv"
}

[[ "$(activation_bundle_preflight)" == absent ]] \
  || fail_test 'all-absent bundle was not accepted'
verify_source_bundle

mkdir -p "$(dirname -- "${SUDOERS}")"
mkdir -p "$(dirname -- "${RELEASE_STATE_ROOT}")"
chmod 2770 "$(dirname -- "${RELEASE_STATE_ROOT}")"
applied="$(apply_install)"
jq -e '.state == "verified" and .releaseEnabledByDefault == false' \
  <<<"${applied}" >/dev/null || fail_test 'sandbox apply did not return exact verification'
[[ "$("${STAT_BIN}" -c %a "${RELEASE_STATE_ROOT}")" == 700 ]] \
  || fail_test 'apply retained an inherited setgid bit on the journal root'
[[ "$(activation_bundle_preflight)" == current ]] \
  || fail_test 'sandbox apply did not install the exact current bundle'
printf 'retained after apply\n' >"${RELEASE_STATE_ROOT}/apply-operation.json"
apply_retained_before="$(sha256sum "${RELEASE_STATE_ROOT}/apply-operation.json")"
apply_install >/dev/null
[[ "${apply_retained_before}" == \
    "$(sha256sum "${RELEASE_STATE_ROOT}/apply-operation.json")" ]] \
  || fail_test 'idempotent apply changed a retained release operation'
bundle_reset

bundle_create_current
printf 'reviewed release predecessor fixture\n' >"${RELEASE_PROGRAM}"
chmod 0755 "${RELEASE_PROGRAM}"
RELEASE_PROGRAM_PREDECESSOR_SHA256="$(sha256sum "${RELEASE_PROGRAM}" | cut -d' ' -f1)"
[[ "$(activation_bundle_preflight)" == current ]] \
  || fail_test 'reviewed release predecessor was not accepted for upgrade'
if ( verify ) >/dev/null 2>&1; then
  fail_test 'installed verifier accepted the release predecessor as current'
fi
apply_install >/dev/null
[[ "$(sha256sum "${RELEASE_PROGRAM}" | cut -d' ' -f1)" == "${RELEASE_PROGRAM_SHA256}" ]] \
  || fail_test 'release predecessor was not upgraded to the exact source'
RELEASE_PROGRAM_PREDECESSOR_SHA256=6e721a7d166fae977d781a5d6341fd872e51cb0bdf349afd8d53da2ec08402c1
bundle_reset

mkdir -p "$(dirname -- "${PROGRAM}")"
cp "${SOURCE_DIR}/atenea-workspace-activation-v1.sh" "${PROGRAM}"
chmod 0755 "${PROGRAM}"
if ( activation_bundle_preflight ) >/dev/null 2>&1; then
  fail_test 'partial activation bundle was accepted'
fi

bundle_reset
bundle_create_predecessor
[[ "$(activation_bundle_preflight)" == predecessor ]] \
  || fail_test 'exact predecessor activation bundle was not accepted'

mkdir -p "${RELEASE_STATE_ROOT}"
chmod 0700 "${RELEASE_STATE_ROOT}"
printf 'retained journal\n' >"${RELEASE_STATE_ROOT}/retained-operation.json"
[[ "$(activation_bundle_preflight)" == predecessor ]] \
  || fail_test 'predecessor rejected retained release journals'

bundle_reset
bundle_create_current
[[ "$(activation_bundle_preflight)" == current ]] \
  || fail_test 'exact current activation bundle was not accepted'
verified="$(verify)"
jq -e '.state == "verified" and .projectId == "atenea" and
  .releaseEnabledByDefault == false and .arbitraryAuthority == false' \
  <<<"${verified}" >/dev/null || fail_test 'installed verifier result is not closed'

bundle_reset
bundle_create_predecessor
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
PROGRAM_PREDECESSOR_SHA256=61fc03da468f2f9fa1fb101dc42129a773f02acaacbc40fd46e18d7a06724df2
bundle_create_current
mv "${PROGRAM}" "${PROGRAM}.target"
ln -s "${PROGRAM}.target" "${PROGRAM}"
if ( activation_bundle_preflight ) >/dev/null 2>&1; then
  fail_test 'symlinked activation program was accepted'
fi

bundle_reset
bundle_create_current
printf 'foreign dependency\n' >"${WORKER_BUNDLE}/${DEPENDENCIES[1]}"
chmod 0750 "${WORKER_BUNDLE}/${DEPENDENCIES[1]}"
if ( activation_bundle_preflight ) >/dev/null 2>&1; then
  fail_test 'foreign activation dependency was accepted'
fi

bundle_reset
bundle_create_current
printf 'foreign release mediator\n' >"${RELEASE_PROGRAM}"
chmod 0755 "${RELEASE_PROGRAM}"
before="$(find "${TEST_ROOT}" -type f -print0 | sort -z | xargs -0 sha256sum)"
if ( rollback_install ) >/dev/null 2>&1; then
  fail_test 'rollback removed a foreign release mediator'
fi
after="$(find "${TEST_ROOT}" -type f -print0 | sort -z | xargs -0 sha256sum)"
[[ "${before}" == "${after}" ]] || fail_test 'rejected rollback modified a foreign bundle'

bundle_reset
bundle_create_current
chmod 0600 "${SUDOERS}"
printf '%s\n' \
  'atenea-worker ALL=(root) NOPASSWD: /usr/local/libexec/atenea/atenea-workspace-release-v1.py *' \
  >>"${SUDOERS}"
chmod 0440 "${SUDOERS}"
before="$(find "${TEST_ROOT}" -type f -print0 | sort -z | xargs -0 sha256sum)"
if ( rollback_install ) >/dev/null 2>&1; then
  fail_test 'rollback accepted broadened release sudo authority'
fi
after="$(find "${TEST_ROOT}" -type f -print0 | sort -z | xargs -0 sha256sum)"
[[ "${before}" == "${after}" ]] || fail_test 'rejected broad sudoers was modified'

bundle_reset
bundle_create_current
printf 'retained operation\n' >"${RELEASE_STATE_ROOT}/operation.json"
printf 'unrelated retained\n' >"${TEST_ROOT}/unrelated-operation"
before_retained="$(sha256sum "${RELEASE_STATE_ROOT}/operation.json" \
  "${TEST_ROOT}/unrelated-operation")"
first_rollback="$(rollback_install)"
jq -e '.state == "rolled-back" and .changed == true and
  .releaseAuthority == false and .retainedJournals == true' \
  <<<"${first_rollback}" >/dev/null || fail_test 'first rollback result is not exact'
[[ ! -e "${RELEASE_PROGRAM}" && "$(activation_bundle_preflight)" == predecessor ]] \
  || fail_test 'rollback did not restore the exact predecessor'
[[ "$(cat "${SUDOERS}")" == "$(predecessor_sudoers_content)" ]] \
  || fail_test 'rollback did not remove only release sudo authority'
after_retained="$(sha256sum "${RELEASE_STATE_ROOT}/operation.json" \
  "${TEST_ROOT}/unrelated-operation")"
[[ "${before_retained}" == "${after_retained}" ]] \
  || fail_test 'rollback changed retained or unrelated operations'
second_rollback="$(rollback_install)"
jq -e '.changed == false and .releaseAuthority == false' \
  <<<"${second_rollback}" >/dev/null || fail_test 'repeated rollback was not idempotent'

bundle_reset
bundle_create_current
chmod 0600 "${SUDOERS}"
predecessor_sudoers_content >"${SUDOERS}"
chmod 0440 "${SUDOERS}"
[[ "$(activation_bundle_preflight)" == rollback-disabled ]] \
  || fail_test 'disabled rollback successor was not recognized'
rollback_install >/dev/null
[[ "$(activation_bundle_preflight)" == predecessor ]] \
  || fail_test 'interrupted rollback did not resume to the predecessor'

[[ "$(sudoers_content | wc -l)" -eq 3 ]] || fail_test 'sudoers rule count is not exact'
[[ "$(sudoers_content | grep -Fxc \
  "atenea-worker ALL=(root) NOPASSWD: ${RELEASE_PROGRAM}")" -eq 1 ]] \
  || fail_test 'release sudo authority without arguments is missing'
[[ "$(sudoers_content | grep -Fxc \
  "atenea-worker ALL=(root) NOPASSWD: ${RELEASE_PROGRAM} --diagnose-capacity-owner")" \
  -eq 1 ]] || fail_test 'capacity diagnosis sudo authority is not exact'
! sudoers_content | grep -F "${RELEASE_PROGRAM} *" >/dev/null \
  || fail_test 'release sudo authority is broadened'
grep -Fq 'installed activation bundle changed after preflight' \
  "${SOURCE_DIR}/install-atenea-routing-activation-v1.sh" \
  || fail_test 'apply does not repeat the whole-bundle preflight before writing'
grep -Fq 'installed activation bundle changed after rollback preflight' \
  "${SOURCE_DIR}/install-atenea-routing-activation-v1.sh" \
  || fail_test 'rollback does not repeat the whole-bundle preflight before writing'

printf 'Atenea workspace activation/release installer and rollback tests passed\n'
