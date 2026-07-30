#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_ROOT="$(mktemp -d /tmp/atenea-backup-config-test.XXXXXXXX)"
CONFIG_ROOT="${TEST_ROOT}/etc/atenea-backup"
trap 'find "${TEST_ROOT}" -depth -delete 2>/dev/null || true' EXIT

KEY_ID='00112233445566778899aabb'
APPLICATION_KEY='Synthetic_App_Key-00112233445566778899'
PASSWORD='Synthetic-Restic-Password-00112233445566778899'

run_helper() {
  printf '%s\n%s\n%s\n%s\n' \
    "${KEY_ID}" "${APPLICATION_KEY}" "${PASSWORD}" "${PASSWORD}" |
    ATENEA_BACKUP_CONFIGURE_TEST_MODE=1 \
    ATENEA_BACKUP_CONFIG_ROOT="${CONFIG_ROOT}" \
      "${SCRIPT_DIR}/configure-external-backup-v1.sh"
}

run_helper >/dev/null
[[ "$(stat -c '%a' "${CONFIG_ROOT}/repository.env")" == "600" ]]
[[ "$(stat -c '%a' "${CONFIG_ROOT}/repository-password")" == "600" ]]
grep -Fx 'RESTIC_REPOSITORY=b2:codynwave-atenea-ax42-backup-eu1:restic/ax42' \
  "${CONFIG_ROOT}/repository.env" >/dev/null
grep -Fx "B2_ACCOUNT_ID=${KEY_ID}" "${CONFIG_ROOT}/repository.env" >/dev/null
grep -Fx "B2_ACCOUNT_KEY=${APPLICATION_KEY}" "${CONFIG_ROOT}/repository.env" >/dev/null
grep -Fx "${PASSWORD}" "${CONFIG_ROOT}/repository-password" >/dev/null

BEFORE="$(sha256sum "${CONFIG_ROOT}/repository.env" \
  "${CONFIG_ROOT}/repository-password" | sha256sum | cut -d' ' -f1)"
set +e
printf '%s\n%s\n%s\n%s\n' \
  "${KEY_ID}" "${APPLICATION_KEY}" "${PASSWORD}" 'different-confirmation' |
  ATENEA_BACKUP_CONFIGURE_TEST_MODE=1 \
  ATENEA_BACKUP_CONFIG_ROOT="${CONFIG_ROOT}" \
    "${SCRIPT_DIR}/configure-external-backup-v1.sh" >/dev/null 2>&1
EXIT_CODE=$?
set -e
[[ "${EXIT_CODE}" -eq 65 ]]
AFTER="$(sha256sum "${CONFIG_ROOT}/repository.env" \
  "${CONFIG_ROOT}/repository-password" | sha256sum | cut -d' ' -f1)"
[[ "${BEFORE}" == "${AFTER}" ]]

printf '%s\n' 'configure-external-backup-v1 tests passed'
