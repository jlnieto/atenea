#!/usr/bin/env bash
set -Eeuo pipefail

BACKUP_ROOT=/var/backups/atenea-worker-bootstrap
ACTION="${1:-}"
SNAPSHOT="${2:-}"

[[ "${EUID}" -eq 0 ]] || { echo "Run as root." >&2; exit 77; }

case "${ACTION}" in
  list)
    find "${BACKUP_ROOT}" -mindepth 1 -maxdepth 1 -type d -printf '%p\n' 2>/dev/null | sort
    ;;
  dry-run)
    [[ -n "${SNAPSHOT}" && "${SNAPSHOT}" == "${BACKUP_ROOT}/"* && -d "${SNAPSHOT}" ]] || { echo "Exact snapshot path required." >&2; exit 65; }
    find "${SNAPSHOT}" -type f -printf 'would restore %P\n' | sort
    echo "Would validate sshd, reload SSH and reload UFW while preserving the current recovery shell."
    ;;
  restore)
    [[ -n "${SNAPSHOT}" && "${SNAPSHOT}" == "${BACKUP_ROOT}/"* && -d "${SNAPSHOT}" ]] || { echo "Exact snapshot path required." >&2; exit 65; }
    echo "Refusing unattended restore. Set ATENEA_WORKER_CONFIRM_RESTORE=YES from an open recovery shell." >&2
    [[ "${ATENEA_WORKER_CONFIRM_RESTORE:-}" == YES ]] || exit 78
    for relative in etc/ssh/sshd_config etc/ssh/sshd_config.d etc/default/ufw etc/ufw etc/systemd/system/atenea-worker-health.service etc/systemd/system/atenea-worker-health.timer; do
      [[ -e "${SNAPSHOT}/${relative}" ]] || continue
      rm -rf -- "/${relative}"
      cp -a -- "${SNAPSHOT}/${relative}" "/${relative}"
    done
    sshd -t
    systemctl daemon-reload
    systemctl reload ssh.service
    ufw reload || true
    echo "Snapshot restored and services reloaded. Prove a new SSH connection now."
    ;;
  *)
    echo "Usage: sudo $0 {list|dry-run SNAPSHOT|restore SNAPSHOT}" >&2
    exit 64
    ;;
esac
