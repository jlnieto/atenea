#!/usr/bin/env bash
set -Eeuo pipefail
set +x
umask 0077

TEST_MODE="${ATENEA_BACKUP_CONFIGURE_TEST_MODE:-0}"
CONFIG_ROOT="${ATENEA_BACKUP_CONFIG_ROOT:-/etc/atenea-backup}"
REPOSITORY="b2:codynwave-atenea-ax42-backup-eu1:restic/ax42"
ENV_FILE="${CONFIG_ROOT}/repository.env"
PASSWORD_FILE="${CONFIG_ROOT}/repository-password"
TEMP_ENV=""
TEMP_PASSWORD=""

fail() {
  printf 'CONFIGURATION_BLOCKED: %s\n' "$*" >&2
  exit 65
}

cleanup() {
  for temporary in "${TEMP_ENV}" "${TEMP_PASSWORD}"; do
    if [[ -n "${temporary}" && -f "${temporary}" && ! -L "${temporary}" ]]; then
      truncate -s 0 -- "${temporary}" 2>/dev/null || true
      rm -f -- "${temporary}"
    fi
  done
}
trap cleanup EXIT

if [[ "${TEST_MODE}" == "1" ]]; then
  [[ "${CONFIG_ROOT}" == /tmp/* && "${CONFIG_ROOT}" != *".."* ]] ||
    fail "test configuration root must be a dedicated path beneath /tmp"
else
  [[ "$(id -u)" -eq 0 ]] || fail "run this helper through sudo"
  [[ -t 0 && -t 1 ]] || fail "interactive terminal input is required"
  [[ "${CONFIG_ROOT}" == "/etc/atenea-backup" ]] ||
    fail "production configuration root is fixed"
fi

install -d -m 0700 "${CONFIG_ROOT}"
[[ -d "${CONFIG_ROOT}" && ! -L "${CONFIG_ROOT}" ]] ||
  fail "configuration root is missing or unsafe"

IFS= read -r -s -p "Backblaze keyID: " KEY_ID
printf '\n'
IFS= read -r -s -p "Backblaze applicationKey: " APPLICATION_KEY
printf '\n'
IFS= read -r -s -p "New Restic repository password: " REPOSITORY_PASSWORD
printf '\n'
IFS= read -r -s -p "Confirm Restic repository password: " PASSWORD_CONFIRMATION
printf '\n'

[[ "${KEY_ID}" =~ ^[A-Za-z0-9]{10,64}$ ]] ||
  fail "keyID format is invalid"
[[ "${APPLICATION_KEY}" =~ ^[A-Za-z0-9_-]{20,128}$ ]] ||
  fail "applicationKey format is invalid"
[[ "${#REPOSITORY_PASSWORD}" -ge 32 && "${#REPOSITORY_PASSWORD}" -le 256 ]] ||
  fail "Restic password must contain between 32 and 256 characters"
[[ "${REPOSITORY_PASSWORD}" == "${PASSWORD_CONFIRMATION}" ]] ||
  fail "Restic password confirmation does not match"

TEMP_ENV="$(mktemp "${CONFIG_ROOT}/.repository.env.XXXXXXXX")"
TEMP_PASSWORD="$(mktemp "${CONFIG_ROOT}/.repository-password.XXXXXXXX")"
chmod 0600 "${TEMP_ENV}" "${TEMP_PASSWORD}"

{
  printf 'RESTIC_REPOSITORY=%s\n' "${REPOSITORY}"
  printf 'B2_ACCOUNT_ID=%s\n' "${KEY_ID}"
  printf 'B2_ACCOUNT_KEY=%s\n' "${APPLICATION_KEY}"
} >"${TEMP_ENV}"
printf '%s\n' "${REPOSITORY_PASSWORD}" >"${TEMP_PASSWORD}"

if [[ "${TEST_MODE}" != "1" ]]; then
  chown root:root "${TEMP_ENV}" "${TEMP_PASSWORD}"
fi
mv -T -- "${TEMP_ENV}" "${ENV_FILE}"
TEMP_ENV=""
mv -T -- "${TEMP_PASSWORD}" "${PASSWORD_FILE}"
TEMP_PASSWORD=""

[[ "$(stat -c '%a' "${ENV_FILE}")" == "600" &&
    "$(stat -c '%a' "${PASSWORD_FILE}")" == "600" ]] ||
  fail "repository input modes are invalid"
if [[ "${TEST_MODE}" != "1" ]]; then
  [[ "$(stat -c '%U:%G' "${ENV_FILE}")" == "root:root" &&
      "$(stat -c '%U:%G' "${PASSWORD_FILE}")" == "root:root" ]] ||
    fail "repository input ownership is invalid"
fi

unset KEY_ID APPLICATION_KEY REPOSITORY_PASSWORD PASSWORD_CONFIRMATION
printf '%s\n' \
  'External repository inputs installed securely; no value was displayed.'
