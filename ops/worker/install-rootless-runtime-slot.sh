#!/usr/bin/env bash

set -Eeuo pipefail

SLOT_NUMBER="${1:-}"
ADMIN_USER="${ATENEA_WORKER_ADMIN_USER:-jose}"
DOCKER_VERSION="${ATENEA_DOCKER_VERSION:-5:29.6.2-1~ubuntu.24.04~noble}"
CONTAINERD_VERSION="${ATENEA_CONTAINERD_VERSION:-2.2.6-1~ubuntu.24.04~noble}"
BUILDX_VERSION="${ATENEA_BUILDX_VERSION:-0.35.0-1~ubuntu.24.04~noble}"
COMPOSE_VERSION="${ATENEA_COMPOSE_VERSION:-5.3.1-1~ubuntu.24.04~noble}"
DOCKER_KEY_FINGERPRINT="9DC858229FC7DD38854AE2D88D81803C0EBFCD88"

usage() {
  echo "Usage: sudo $0 SLOT_NUMBER (1-4)" >&2
  exit 64
}

[[ "${EUID}" -eq 0 ]] || {
  echo "Run as root." >&2
  exit 77
}
[[ "${SLOT_NUMBER}" =~ ^[1-4]$ ]] || usage

. /etc/os-release
[[ "${ID}" == "ubuntu" && "${VERSION_CODENAME}" == "noble" ]] || {
  echo "This pinned runtime supports Ubuntu 24.04 noble only." >&2
  exit 69
}

slot_user="atenea-slot${SLOT_NUMBER}"
slot_uid="$((1100 + SLOT_NUMBER))"
slot_home="/var/lib/atenea-slots/slot${SLOT_NUMBER}"
subid_start="$((300000 + (SLOT_NUMBER - 1) * 65536))"
subid_end="$((subid_start + 65535))"
runtime_dir="/run/user/${slot_uid}"

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl gnupg uidmap dbus-user-session \
  slirp4netns fuse-overlayfs acl

install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
actual_fingerprint="$(
  gpg --show-keys --with-colons /etc/apt/keyrings/docker.asc |
    awk -F: '$1 == "fpr" { print $10; exit }'
)"
[[ "${actual_fingerprint}" == "${DOCKER_KEY_FINGERPRINT}" ]] || {
  echo "Unexpected Docker signing-key fingerprint: ${actual_fingerprint}" >&2
  exit 65
}

architecture="$(dpkg --print-architecture)"
cat >/etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: noble
Components: stable
Architectures: ${architecture}
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt-get update

# Rootful Docker is deliberately unavailable. Each slot gets its own rootless
# daemon and cgroup boundary.
systemctl mask docker.service docker.socket containerd.service
apt-get install -y --allow-downgrades \
  "docker-ce=${DOCKER_VERSION}" \
  "docker-ce-cli=${DOCKER_VERSION}" \
  "containerd.io=${CONTAINERD_VERSION}" \
  "docker-buildx-plugin=${BUILDX_VERSION}" \
  "docker-compose-plugin=${COMPOSE_VERSION}" \
  "docker-ce-rootless-extras=${DOCKER_VERSION}"
systemctl disable --now docker.service docker.socket containerd.service \
  >/dev/null 2>&1 || true

apt-mark hold docker-ce docker-ce-cli containerd.io docker-buildx-plugin \
  docker-compose-plugin docker-ce-rootless-extras >/dev/null

install -d -m 0755 /var/lib/atenea-slots
if ! id "${slot_user}" >/dev/null 2>&1; then
  useradd --uid "${slot_uid}" --create-home --home-dir "${slot_home}" \
    --shell /bin/bash "${slot_user}"
fi
[[ "$(id -u "${slot_user}")" == "${slot_uid}" ]] || {
  echo "Unexpected UID for ${slot_user}." >&2
  exit 65
}

usermod --del-subuids 0-4294967295 "${slot_user}" 2>/dev/null || true
usermod --del-subgids 0-4294967295 "${slot_user}" 2>/dev/null || true
usermod --add-subuids "${subid_start}-${subid_end}" "${slot_user}"
usermod --add-subgids "${subid_start}-${subid_end}" "${slot_user}"
usermod -aG "${slot_user}" "${ADMIN_USER}"

install -d -m 0755 "/etc/systemd/system/user-${slot_uid}.slice.d"
cat >"/etc/systemd/system/user-${slot_uid}.slice.d/limits.conf" <<EOF
[Slice]
CPUQuota=400%
MemoryHigh=10G
MemoryMax=12G
TasksMax=4096
EOF
systemctl daemon-reload

loginctl enable-linger "${slot_user}"
systemctl start "user@${slot_uid}.service"
for unused in {1..20}; do
  [[ -S "${runtime_dir}/bus" ]] && break
  sleep 0.25
done
[[ -S "${runtime_dir}/bus" ]] || {
  echo "The systemd user bus did not start for ${slot_user}." >&2
  exit 70
}

slot_env=(
  "HOME=${slot_home}"
  "USER=${slot_user}"
  "LOGNAME=${slot_user}"
  "XDG_RUNTIME_DIR=${runtime_dir}"
  "DBUS_SESSION_BUS_ADDRESS=unix:path=${runtime_dir}/bus"
  "PATH=/usr/bin:/usr/local/bin:/bin"
)

if [[ ! -f "${slot_home}/.config/systemd/user/docker.service" ]]; then
  runuser -u "${slot_user}" -- env "${slot_env[@]}" \
    dockerd-rootless-setuptool.sh install --force
fi
runuser -u "${slot_user}" -- env "${slot_env[@]}" \
  systemctl --user enable --now docker.service

socket="${runtime_dir}/docker.sock"
for unused in {1..40}; do
  [[ -S "${socket}" ]] && break
  sleep 0.25
done
[[ -S "${socket}" ]] || {
  echo "Rootless Docker socket did not start: ${socket}" >&2
  exit 70
}

runuser -u "${slot_user}" -- env "${slot_env[@]}" \
  DOCKER_HOST="unix://${socket}" docker info >/dev/null

proxy_dir="/run/atenea-runtime/slot${SLOT_NUMBER}"
proxy_socket="${proxy_dir}/docker.sock"
cat >"/etc/tmpfiles.d/atenea-runtime-slot${SLOT_NUMBER}.conf" <<EOF
d ${proxy_dir} 0770 root ${slot_user} -
EOF
systemd-tmpfiles --create \
  "/etc/tmpfiles.d/atenea-runtime-slot${SLOT_NUMBER}.conf"

cat >"/etc/systemd/system/atenea-docker-proxy-slot${SLOT_NUMBER}.socket" <<EOF
[Unit]
Description=Atenea slot ${SLOT_NUMBER} rootless Docker proxy socket

[Socket]
ListenStream=${proxy_socket}
SocketUser=root
SocketGroup=${slot_user}
SocketMode=0660

[Install]
WantedBy=sockets.target
EOF

cat >"/etc/systemd/system/atenea-docker-proxy-slot${SLOT_NUMBER}.service" <<EOF
[Unit]
Description=Atenea slot ${SLOT_NUMBER} rootless Docker socket proxy
Requires=user@${slot_uid}.service
After=user@${slot_uid}.service

[Service]
User=${slot_user}
Group=${slot_user}
ExecStart=/usr/lib/systemd/systemd-socket-proxyd ${socket}
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=read-only
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
RestrictSUIDSGID=yes
LockPersonality=yes
MemoryDenyWriteExecute=yes
EOF

systemctl daemon-reload
systemctl stop "atenea-docker-proxy-slot${SLOT_NUMBER}.service" \
  "atenea-docker-proxy-slot${SLOT_NUMBER}.socket" >/dev/null 2>&1 || true
systemctl enable --now "atenea-docker-proxy-slot${SLOT_NUMBER}.socket"
runuser -u "${ADMIN_USER}" -- env \
  DOCKER_HOST="unix://${proxy_socket}" \
  docker info >/dev/null

if getent group docker >/dev/null; then
  if getent group docker | cut -d: -f4 | grep -Eq '(^|,)(jose|atenea-worker)(,|$)'; then
    echo "A Codex identity unexpectedly belongs to the rootful docker group." >&2
    exit 71
  fi
fi

echo "slot=${SLOT_NUMBER}"
echo "user=${slot_user}"
echo "uid=${slot_uid}"
echo "socket=${socket}"
echo "admin_proxy=${proxy_socket}"
echo "cpu_quota=400%"
echo "memory_high=10G"
echo "memory_max=12G"
echo "rootful_docker=masked"
