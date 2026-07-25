#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE_DIR="${SCRIPT_DIR}/templates"
ADMIN_USER="${ATENEA_WORKER_ADMIN_USER:-jose}"
CODEX_RELEASE="${ATENEA_CODEX_RELEASE:-0.145.0}"
ADMIN_HOME="$(getent passwd "${ADMIN_USER}" | cut -d: -f6)"

[[ "${EUID}" -eq 0 ]] || {
  echo "Run as root." >&2
  exit 77
}

[[ -n "${ADMIN_HOME}" && -d "${ADMIN_HOME}" ]] || {
  echo "Administrative user or home not found: ${ADMIN_USER}" >&2
  exit 65
}

for template in codex-work; do
  [[ -f "${TEMPLATE_DIR}/${template}" ]] || {
    echo "Missing template: ${TEMPLATE_DIR}/${template}" >&2
    exit 66
  }
done
[[ -x "${SCRIPT_DIR}/promote-codex-context.sh" ]] || {
  echo "Missing context promotion helper." >&2
  exit 66
}

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl bubblewrap apparmor-profiles apparmor-utils

profile_source="/usr/share/apparmor/extra-profiles/bwrap-userns-restrict"
profile_target="/etc/apparmor.d/bwrap-userns-restrict"
[[ -f "${profile_source}" ]] || {
  echo "Required Ubuntu 24.04 bwrap AppArmor profile is unavailable." >&2
  exit 69
}
install -m 0644 "${profile_source}" "${profile_target}"
apparmor_parser -r "${profile_target}"

install -d -m 0700 -o "${ADMIN_USER}" -g "${ADMIN_USER}" "${ADMIN_HOME}/.codex"
install -d -m 0755 -o "${ADMIN_USER}" -g "${ADMIN_USER}" "${ADMIN_HOME}/.local/bin"
install -m 0755 -o "${ADMIN_USER}" -g "${ADMIN_USER}" \
  "${TEMPLATE_DIR}/codex-work" "${ADMIN_HOME}/.local/bin/codex-work"

install -d -m 2770 -o "${ADMIN_USER}" -g atenea \
  /srv/atenea/workspaces/manual

ATENEA_WORKER_ADMIN_USER="${ADMIN_USER}" \
  "${SCRIPT_DIR}/promote-codex-context.sh" apply

runuser -u "${ADMIN_USER}" -- env \
  HOME="${ADMIN_HOME}" \
  PATH="${ADMIN_HOME}/.local/bin:/usr/local/bin:/usr/bin:/bin" \
  CODEX_RELEASE="${CODEX_RELEASE}" \
  CODEX_NON_INTERACTIVE=1 \
  sh -c 'curl -fsSL https://chatgpt.com/codex/install.sh | sh'

installed_version="$(
  runuser -u "${ADMIN_USER}" -- env \
    HOME="${ADMIN_HOME}" \
    PATH="${ADMIN_HOME}/.local/bin:/usr/local/bin:/usr/bin:/bin" \
    codex --version
)"
[[ "${installed_version}" == "codex-cli ${CODEX_RELEASE}" ]] || {
  echo "Unexpected Codex version: ${installed_version}" >&2
  exit 70
}

runuser -u "${ADMIN_USER}" -- \
  /usr/bin/bwrap --ro-bind / / --proc /proc --dev /dev true

echo "Codex administrative bridge installed: ${installed_version}"
if runuser -u "${ADMIN_USER}" -- env \
  HOME="${ADMIN_HOME}" \
  PATH="${ADMIN_HOME}/.local/bin:/usr/local/bin:/usr/bin:/bin" \
  codex login status; then
  echo "Authentication guard: ready"
else
  echo "Authentication guard: action required"
  echo "Run as ${ADMIN_USER}: codex login --device-auth"
fi
