#!/usr/bin/env bash

set -Eeuo pipefail
umask 0077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
LIBEXEC='/usr/libexec'
SUDOERS='/etc/sudoers.d/91-atenea-runtime-v1'
MANAGER_CONTROL_ROOT='/srv/atenea/worker/runtime-manager-v1'
CODEX_IMAGE='sha256:c081aaa9d40afa4d8b57297000fe9aff5635e52a94b2b87abf8626b128c55e2d'
CODEX_IMAGE_ID='sha256:c081aaa9d40afa4d8b57297000fe9aff5635e52a94b2b87abf8626b128c55e2d'
CODEX_DOCKERFILE_SHA256='d6a5688825f46533074d800cd11b29a1656413cdf68249a07a4924b17829d27e'
CODEX_PROXY_SHA256='b62771d89fe1a26ca804f34c8712c3156b41134d235825c03a90a90daa7de64f'

fail() {
  printf 'INSTALL_ATENEA_RUNTIME_FAILED: %s\n' "$1" >&2
  exit 1
}

[[ "${EUID}" -eq 0 ]] || fail 'installer must run as root'
for command in docker install jq sha256sum stat visudo; do
  command -v "${command}" >/dev/null || fail "missing prerequisite: ${command}"
done

for source in \
  runtime-client-v1.sh \
  runtime-manager-v1.sh \
  runtime-engine-v1.sh \
  atenea-runtime-engine-adapter-v1.sh; do
  [[ -f "${SCRIPT_DIR}/${source}" && ! -L "${SCRIPT_DIR}/${source}" ]] ||
    fail "missing reviewed source: ${source}"
  bash -n "${SCRIPT_DIR}/${source}"
done

dockerfile="${SCRIPT_DIR}/images/atenea-codex-app-server/Dockerfile"
[[ -f "${dockerfile}" && ! -L "${dockerfile}" ]] ||
  fail 'Codex image Dockerfile is missing'
[[ "$(sha256sum "${dockerfile}" | cut -d' ' -f1)" == \
    "${CODEX_DOCKERFILE_SHA256}" ]] ||
  fail 'Codex image Dockerfile SHA-256 differs'
proxy="${SCRIPT_DIR}/images/atenea-codex-app-server/codex-loopback-proxy.mjs"
[[ -f "${proxy}" && ! -L "${proxy}" &&
    "$(sha256sum "${proxy}" | cut -d' ' -f1)" == "${CODEX_PROXY_SHA256}" ]] ||
  fail 'Codex loopback proxy SHA-256 differs'

sudo -n -u atenea-slot3 \
  env DOCKER_HOST=unix:///run/atenea-runtime/slot3/docker.sock \
  docker image inspect "${CODEX_IMAGE}" |
  jq -e --arg id "${CODEX_IMAGE_ID}" '
    length == 1 and .[0].Id == $id and
    .[0].Config.Labels["com.atenea.image"] == "codex-app-server" and
    .[0].Config.Labels["com.atenea.codex.version"] == "0.145.0" and
    .[0].Config.Labels["com.atenea.node.version"] == "22.16.0" and
    .[0].Config.Labels["com.atenea.codex.auth-boundary"] == "loopback-proxy-v1"
  ' >/dev/null ||
  fail 'the exact reviewed Codex App Server image is unavailable in slot3'

install -d -o root -g root -m 0755 "${LIBEXEC}"
install -d -o root -g root -m 2750 "${MANAGER_CONTROL_ROOT}"
install -o root -g root -m 0755 \
  "${SCRIPT_DIR}/runtime-client-v1.sh" \
  "${LIBEXEC}/atenea-runtime-client-v1"
install -o root -g root -m 0755 \
  "${SCRIPT_DIR}/runtime-manager-v1.sh" \
  "${LIBEXEC}/atenea-runtime-manager-v1"
install -o root -g root -m 0755 \
  "${SCRIPT_DIR}/runtime-engine-v1.sh" \
  "${LIBEXEC}/atenea-runtime-engine-v1"
install -o root -g root -m 0755 \
  "${SCRIPT_DIR}/atenea-runtime-engine-adapter-v1.sh" \
  "${LIBEXEC}/atenea-runtime-engine-adapter-v1"

sudoers_temporary="$(mktemp /etc/sudoers.d/.91-atenea-runtime-v1.XXXXXX)"
printf '%s\n' \
  'atenea-worker ALL=(root) NOPASSWD: /usr/libexec/atenea-runtime-manager-v1 *' \
  >"${sudoers_temporary}"
chmod 0440 "${sudoers_temporary}"
visudo -cf "${sudoers_temporary}" >/dev/null
mv "${sudoers_temporary}" "${SUDOERS}"
chown root:root "${SUDOERS}"
chmod 0440 "${SUDOERS}"
visudo -cf "${SUDOERS}" >/dev/null

for installed in \
  "${LIBEXEC}/atenea-runtime-client-v1" \
  "${LIBEXEC}/atenea-runtime-manager-v1" \
  "${LIBEXEC}/atenea-runtime-engine-v1" \
  "${LIBEXEC}/atenea-runtime-engine-adapter-v1"; do
  [[ "$(stat -c %U:%G:%a "${installed}")" == 'root:root:755' ]] ||
    fail "installed boundary metadata differs: ${installed}"
done
[[ "$(stat -c %U:%G:%a "${MANAGER_CONTROL_ROOT}")" == 'root:root:2750' ]] ||
  fail 'installed manager control-root metadata differs'

printf 'ATENEA_RUNTIME_V1_INSTALLED\n'
sha256sum \
  "${LIBEXEC}/atenea-runtime-client-v1" \
  "${LIBEXEC}/atenea-runtime-manager-v1" \
  "${LIBEXEC}/atenea-runtime-engine-v1" \
  "${LIBEXEC}/atenea-runtime-engine-adapter-v1" \
  "${SUDOERS}"
stat -c '%U:%G:%a %n' "${MANAGER_CONTROL_ROOT}"
