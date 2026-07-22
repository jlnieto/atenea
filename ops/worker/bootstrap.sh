#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE_DIR="${SCRIPT_DIR}/templates"
STAGE="${1:-}"
WORKER_HOSTNAME="${ATENEA_WORKER_HOSTNAME:-codex-worker-01}"
ADMIN_USER="${ATENEA_WORKER_ADMIN_USER:-jose}"
ADMIN_PUBKEY_FILE="${ATENEA_WORKER_ADMIN_PUBKEY_FILE:-}"
BACKUP_ROOT="/var/backups/atenea-worker-bootstrap"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"

usage() {
  echo "Usage: sudo $0 {preflight|prepare|ssh|firewall|tailscale-package|monitoring}" >&2
  exit 64
}

[[ -n "${STAGE}" ]] || usage
[[ "${EUID}" -eq 0 ]] || { echo "Run as root." >&2; exit 77; }

backup_path() {
  local source="$1" destination="$2"
  if [[ -e "${source}" ]]; then
    mkdir -p "$(dirname -- "${destination}")"
    cp -a -- "${source}" "${destination}"
  fi
}

begin_snapshot() {
  SNAPSHOT_DIR="${BACKUP_ROOT}/${RUN_ID}-${STAGE}"
  install -d -m 0700 "${SNAPSHOT_DIR}"
  backup_path /etc/ssh/sshd_config "${SNAPSHOT_DIR}/etc/ssh/sshd_config"
  backup_path /etc/ssh/sshd_config.d "${SNAPSHOT_DIR}/etc/ssh/sshd_config.d"
  backup_path /etc/default/ufw "${SNAPSHOT_DIR}/etc/default/ufw"
  backup_path /etc/ufw "${SNAPSHOT_DIR}/etc/ufw"
  backup_path /etc/systemd/system/atenea-worker-health.service "${SNAPSHOT_DIR}/etc/systemd/system/atenea-worker-health.service"
  backup_path /etc/systemd/system/atenea-worker-health.timer "${SNAPSHOT_DIR}/etc/systemd/system/atenea-worker-health.timer"
  hostnamectl status >"${SNAPSHOT_DIR}/hostnamectl.txt"
  ss -lntup >"${SNAPSHOT_DIR}/listeners.txt"
  cp -a /proc/mdstat "${SNAPSHOT_DIR}/mdstat.txt"
  printf '%s\n' "stage=${STAGE}" "utc=${RUN_ID}" >"${SNAPSHOT_DIR}/manifest.txt"
  echo "Rollback snapshot: ${SNAPSHOT_DIR}"
}

preflight() {
  echo "hostname=$(hostname)"
  echo "os=$(. /etc/os-release && echo "${PRETTY_NAME}")"
  echo "kernel=$(uname -r)"
  echo "cpu_threads=$(nproc)"
  free -h
  df -hT /
  cat /proc/mdstat
  ss -lntup
  sshd -t
  echo "Preflight passed; no changes made."
}

prepare() {
  [[ -n "${ADMIN_PUBKEY_FILE}" && -s "${ADMIN_PUBKEY_FILE}" ]] || {
    echo "Set ATENEA_WORKER_ADMIN_PUBKEY_FILE to the approved public key file." >&2
    exit 65
  }
  grep -Eq '^ssh-ed25519 [A-Za-z0-9+/=]+( .*)?$' "${ADMIN_PUBKEY_FILE}" || {
    echo "The administrator public key must be ED25519." >&2
    exit 65
  }

  begin_snapshot
  export DEBIAN_FRONTEND=noninteractive
  apt-get update
  apt-get -y upgrade
  apt-get install -y ca-certificates curl gnupg jq rsync git ripgrep tmux htop \
    smartmontools mdadm unattended-upgrades ufw sudo

  hostnamectl set-hostname "${WORKER_HOSTNAME}"
  if grep -qE '^127\.0\.1\.1[[:space:]]' /etc/hosts; then
    sed -i -E "s/^127\\.0\\.1\\.1[[:space:]].*/127.0.1.1 ${WORKER_HOSTNAME}/" /etc/hosts
  else
    printf '127.0.1.1 %s\n' "${WORKER_HOSTNAME}" >>/etc/hosts
  fi

  getent group atenea >/dev/null || groupadd --system atenea
  if ! id "${ADMIN_USER}" >/dev/null 2>&1; then
    useradd --create-home --shell /bin/bash "${ADMIN_USER}"
  fi
  usermod -aG sudo,atenea "${ADMIN_USER}"
  install -d -m 0700 -o "${ADMIN_USER}" -g "${ADMIN_USER}" "/home/${ADMIN_USER}/.ssh"
  install -m 0600 -o "${ADMIN_USER}" -g "${ADMIN_USER}" "${ADMIN_PUBKEY_FILE}" "/home/${ADMIN_USER}/.ssh/authorized_keys"
  printf '%s ALL=(ALL:ALL) NOPASSWD: ALL\n' "${ADMIN_USER}" >"/etc/sudoers.d/90-${ADMIN_USER}-atenea-bootstrap"
  chmod 0440 "/etc/sudoers.d/90-${ADMIN_USER}-atenea-bootstrap"
  visudo -cf "/etc/sudoers.d/90-${ADMIN_USER}-atenea-bootstrap"

  if ! id atenea-worker >/dev/null 2>&1; then
    useradd --system --gid atenea --home-dir /var/lib/atenea-worker \
      --create-home --shell /usr/sbin/nologin atenea-worker
  fi
  install -d -m 0750 -o root -g atenea /etc/atenea-worker
  install -d -m 0755 -o root -g atenea /srv/atenea
  for path in worker repositories workspaces caches artifacts backups-staging; do
    install -d -m 2770 -o atenea-worker -g atenea "/srv/atenea/${path}"
  done

  cat >/etc/apt/apt.conf.d/52atenea-unattended-upgrades <<'EOF'
Unattended-Upgrade::Automatic-Reboot "false";
EOF
  systemctl enable --now unattended-upgrades.service
  echo "Prepare complete. Prove a fresh ${ADMIN_USER} login and sudo -n before running the ssh stage."
}

configure_ssh() {
  begin_snapshot
  install -m 0644 "${TEMPLATE_DIR}/00-atenea-worker.conf" /etc/ssh/sshd_config.d/00-atenea-worker.conf
  sshd -t
  sshd -T >"${SNAPSHOT_DIR}/sshd-effective-after.txt"
  systemctl reload ssh.service
  echo "SSH policy reloaded. Keep this shell open and prove fresh ${ADMIN_USER} and root key logins."
}

configure_firewall() {
  begin_snapshot
  sed -i 's/^IPV6=.*/IPV6=yes/' /etc/default/ufw
  ufw default deny incoming
  ufw default allow outgoing
  if ! ufw status | grep -Eq '^22/tcp[[:space:]].*LIMIT'; then
    ufw limit 22/tcp comment 'Atenea SSH break-glass'
  fi
  ufw --force enable
  ufw status verbose >"${SNAPSHOT_DIR}/ufw-status-after.txt"
  echo "Firewall enabled. Prove a fresh SSH connection before closing recovery shells."
}

install_tailscale_package() {
  begin_snapshot
  curl -fsSL https://pkgs.tailscale.com/stable/ubuntu/noble.noarmor.gpg \
    -o /usr/share/keyrings/tailscale-archive-keyring.gpg
  curl -fsSL https://pkgs.tailscale.com/stable/ubuntu/noble.tailscale-keyring.list \
    -o /etc/apt/sources.list.d/tailscale.list
  apt-get update
  apt-get install -y tailscale
  systemctl enable --now tailscaled.service
  install -d -m 0750 -o root -g atenea /etc/atenea-worker/gates
  cat >/etc/atenea-worker/gates/tailscale-enrollment.pending <<'EOF'
Pending operator decision: tailnet owner, second recovery administrator,
worker tag, least-privilege ACL and device enrollment/recovery procedure.
Do not store a reusable personal auth key on this host.
EOF
  chmod 0640 /etc/atenea-worker/gates/tailscale-enrollment.pending
  echo "Tailscale package installed but not enrolled. Operator gate remains open."
}

install_monitoring() {
  begin_snapshot
  install -m 0755 "${SCRIPT_DIR}/verify.sh" /usr/local/sbin/atenea-worker-verify
  install -m 0644 "${TEMPLATE_DIR}/atenea-worker-health.service" /etc/systemd/system/atenea-worker-health.service
  install -m 0644 "${TEMPLATE_DIR}/atenea-worker-health.timer" /etc/systemd/system/atenea-worker-health.timer
  systemctl daemon-reload
  systemctl enable --now atenea-worker-health.timer
  /usr/local/sbin/atenea-worker-verify --json --strict
}

case "${STAGE}" in
  preflight) preflight ;;
  prepare) prepare ;;
  ssh) configure_ssh ;;
  firewall) configure_firewall ;;
  tailscale-package) install_tailscale_package ;;
  monitoring) install_monitoring ;;
  *) usage ;;
esac
