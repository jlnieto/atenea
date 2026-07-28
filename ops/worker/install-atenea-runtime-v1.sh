#!/usr/bin/env bash

set -Eeuo pipefail
umask 0077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
LIBEXEC='/usr/libexec'
SUDOERS='/etc/sudoers.d/91-atenea-runtime-v1'
CODEX_IMAGE='atenea/codex-app-server@sha256:b51c22f9c49b8c3196bda81669265ef0e552c6598d02c48eb370ed32f80611a5'
CODEX_IMAGE_ID='sha256:b51c22f9c49b8c3196bda81669265ef0e552c6598d02c48eb370ed32f80611a5'
CODEX_DOCKERFILE_SHA256='628cf76fb87da3becadc873c99c02113ad74e38eb64383e929e7663ec3d79ae9'

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

sudo -n -u atenea-slot2 \
  env DOCKER_HOST=unix:///run/atenea-runtime/slot2/docker.sock \
  docker image inspect "${CODEX_IMAGE}" |
  jq -e --arg id "${CODEX_IMAGE_ID}" '
    length == 1 and .[0].Id == $id and
    .[0].Config.Labels["com.atenea.image"] == "codex-app-server" and
    .[0].Config.Labels["com.atenea.codex.version"] == "0.145.0" and
    .[0].Config.Labels["com.atenea.node.version"] == "22.16.0"
  ' >/dev/null ||
  fail 'the exact reviewed Codex App Server image is unavailable in slot2'

install -d -o root -g root -m 0755 "${LIBEXEC}"
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

printf 'ATENEA_RUNTIME_V1_INSTALLED\n'
sha256sum \
  "${LIBEXEC}/atenea-runtime-client-v1" \
  "${LIBEXEC}/atenea-runtime-manager-v1" \
  "${LIBEXEC}/atenea-runtime-engine-v1" \
  "${LIBEXEC}/atenea-runtime-engine-adapter-v1" \
  "${SUDOERS}"
