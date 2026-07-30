#!/usr/bin/env bash
set -Eeuo pipefail

ACTION="${1:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROGRAM="/usr/local/libexec/atenea/external-backup-v1.py"
CONFIGURE_PROGRAM="/usr/local/libexec/atenea/configure-external-backup-v1.sh"
CONFIG_ROOT="/etc/atenea-backup"
STATE_ROOT="/var/lib/atenea-external-backup-v1"
STAGING_ROOT="/srv/atenea/backups-staging"
UNITS=(
  atenea-external-backup-v1.service
  atenea-external-backup-v1.timer
  atenea-external-backup-check-v1.service
  atenea-external-backup-check-v1.timer
)
TIMERS=(
  atenea-external-backup-v1.timer
  atenea-external-backup-check-v1.timer
)

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 65
}

require_root() {
  [[ "$(id -u)" -eq 0 ]] || fail "run this action as root"
}

validate_sources() {
  [[ -f "${SCRIPT_DIR}/external-backup-v1.py" ]] ||
    fail "external backup program is missing"
  [[ -f "${SCRIPT_DIR}/configure-external-backup-v1.sh" ]] ||
    fail "external backup configuration helper is missing"
  for unit in "${UNITS[@]}"; do
    [[ -f "${SCRIPT_DIR}/templates/${unit}" ]] ||
      fail "systemd template is missing: ${unit}"
  done
}

plan() {
  validate_sources
  jq -n '{
    schemaVersion: 1,
    action: "install-disabled",
    engine: "restic",
    workerId: "ax42-01",
    policyTag: "atenea-authoritative-v1",
    retention: {daily: 14, weekly: 8, monthly: 12},
    credentialsCreated: false,
    credentialsRead: false,
    timersEnabled: false,
    routingChanged: false
  }'
}

apply_install() {
  require_root
  validate_sources
  command -v restic >/dev/null || fail "restic must be installed before apply"
  install -d -o root -g root -m 0755 /usr/local/libexec/atenea
  install -o root -g root -m 0755 \
    "${SCRIPT_DIR}/external-backup-v1.py" "${PROGRAM}"
  install -o root -g root -m 0755 \
    "${SCRIPT_DIR}/configure-external-backup-v1.sh" "${CONFIGURE_PROGRAM}"
  install -d -o root -g root -m 0700 "${CONFIG_ROOT}"
  install -d -o root -g root -m 0700 "${STATE_ROOT}"
  install -d -o atenea-worker -g atenea -m 2770 "${STAGING_ROOT}"
  install -d -o atenea-worker -g atenea -m 2770 \
    "${STAGING_ROOT}/restore-tests"
  for unit in "${UNITS[@]}"; do
    install -o root -g root -m 0644 \
      "${SCRIPT_DIR}/templates/${unit}" "/etc/systemd/system/${unit}"
  done
  systemctl daemon-reload
  systemctl disable --now "${TIMERS[@]}" >/dev/null 2>&1 || true
  verify
}

verify() {
  require_root
  [[ "$(stat -c '%a:%U:%G' "${CONFIG_ROOT}")" == "700:root:root" ]] ||
    fail "configuration root ownership or mode is invalid"
  [[ "$(stat -c '%a:%U:%G' "${STATE_ROOT}")" == "700:root:root" ]] ||
    fail "state root ownership or mode is invalid"
  [[ -x "${PROGRAM}" && ! -L "${PROGRAM}" ]] ||
    fail "installed backup program is missing or unsafe"
  [[ -x "${CONFIGURE_PROGRAM}" && ! -L "${CONFIGURE_PROGRAM}" ]] ||
    fail "installed backup configuration helper is missing or unsafe"
  for unit in "${UNITS[@]}"; do
    systemd-analyze verify "/etc/systemd/system/${unit}" >/dev/null
  done
  for timer in "${TIMERS[@]}"; do
    [[ "$(systemctl is-enabled "${timer}" 2>/dev/null || true)" == "disabled" ]] ||
      fail "timer must remain disabled before external acceptance: ${timer}"
  done
  printf '%s\n' 'external-backup-v1 disabled installation verified'
}

enable_timers() {
  require_root
  for file in repository.env repository-password; do
    [[ -f "${CONFIG_ROOT}/${file}" && ! -L "${CONFIG_ROOT}/${file}" ]] ||
      fail "external repository input is missing or unsafe: ${file}"
    [[ "$(stat -c '%a:%U:%G' "${CONFIG_ROOT}/${file}")" == "600:root:root" ]] ||
      fail "external repository input must be root:root mode 0600: ${file}"
  done
  systemctl enable --now "${TIMERS[@]}"
  systemctl is-enabled "${TIMERS[@]}"
}

disable_timers() {
  require_root
  systemctl disable --now "${TIMERS[@]}" >/dev/null 2>&1 || true
  printf '%s\n' 'external backup timers disabled; repository and credentials preserved'
}

rollback() {
  require_root
  disable_timers
  systemctl stop atenea-external-backup-v1.service \
    atenea-external-backup-check-v1.service >/dev/null 2>&1 || true
  for unit in "${UNITS[@]}"; do
    [[ ! -e "/etc/systemd/system/${unit}" || -f "/etc/systemd/system/${unit}" ]] ||
      fail "refusing to remove unsafe unit path: ${unit}"
    rm -f -- "/etc/systemd/system/${unit}"
  done
  [[ ! -e "${PROGRAM}" || (-f "${PROGRAM}" && ! -L "${PROGRAM}") ]] ||
    fail "refusing to remove unsafe installed program"
  [[ ! -e "${CONFIGURE_PROGRAM}" ||
      (-f "${CONFIGURE_PROGRAM}" && ! -L "${CONFIGURE_PROGRAM}") ]] ||
    fail "refusing to remove unsafe installed configuration helper"
  rm -f -- "${PROGRAM}"
  rm -f -- "${CONFIGURE_PROGRAM}"
  systemctl daemon-reload
  printf '%s\n' 'external backup automation rolled back; repository, credentials, state and evidence preserved'
}

case "${ACTION}" in
  plan) plan ;;
  apply) apply_install ;;
  verify) verify ;;
  enable) enable_timers ;;
  disable) disable_timers ;;
  rollback) rollback ;;
  *) fail "usage: $0 plan|apply|verify|enable|disable|rollback" ;;
esac
