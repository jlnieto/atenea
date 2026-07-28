#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
LOCK_FILE="${SCRIPT_DIR}/toolchain-lock-v1.sh"
ACTION="${1:-}"
SLOT_NUMBER="${2:-}"

usage() {
  cat >&2 <<'EOF'
Usage:
  install-toolchain-prerequisites.sh plan
  sudo install-toolchain-prerequisites.sh install-host
  sudo install-toolchain-prerequisites.sh install-images SLOT_NUMBER
  sudo install-toolchain-prerequisites.sh install-playwright-module SLOT_NUMBER
  sudo install-toolchain-prerequisites.sh verify-host
  sudo install-toolchain-prerequisites.sh verify-slot SLOT_NUMBER

SLOT_NUMBER must be between 1 and 4. Image installation is idempotent and
targets only that slot's rootless daemon. The Playwright module installation
uses the selected slot for a package-lock-enforced download, then installs a
content-verified, read-only host bundle shared by the browser containers.
EOF
  exit 64
}

[[ -r "${LOCK_FILE}" ]] || {
  echo "Missing toolchain lock: ${LOCK_FILE}" >&2
  exit 66
}
# shellcheck source=toolchain-lock-v1.sh
. "${LOCK_FILE}"

require_root() {
  [[ "${EUID}" -eq 0 ]] || {
    echo "Run this action as root." >&2
    exit 77
  }
}

validate_slot() {
  [[ "${SLOT_NUMBER}" =~ ^[1-4]$ ]] || usage
  id "atenea-slot${SLOT_NUMBER}" >/dev/null 2>&1 || {
    echo "Runtime slot is not installed: atenea-slot${SLOT_NUMBER}" >&2
    exit 69
  }
}

verify_platform() {
  # shellcheck source=/dev/null
  . /etc/os-release
  [[ "${ID}" == "${ATENEA_TOOLCHAIN_OS_ID}" ]] || {
    echo "Unsupported OS: ${ID}" >&2
    exit 69
  }
  [[ "${VERSION_CODENAME}" == "${ATENEA_TOOLCHAIN_OS_CODENAME}" ]] || {
    echo "Unsupported OS codename: ${VERSION_CODENAME}" >&2
    exit 69
  }
  [[ "$(dpkg --print-architecture)" == "${ATENEA_TOOLCHAIN_ARCH}" ]] || {
    echo "Unsupported architecture: $(dpkg --print-architecture)" >&2
    exit 69
  }
}

slot_uid() {
  id -u "atenea-slot${SLOT_NUMBER}"
}

slot_home() {
  getent passwd "atenea-slot${SLOT_NUMBER}" | cut -d: -f6
}

slot_docker() {
  local uid home runtime_dir socket
  uid="$(slot_uid)"
  home="$(slot_home)"
  runtime_dir="/run/user/${uid}"
  socket="${runtime_dir}/docker.sock"
  [[ -S "${socket}" ]] || {
    echo "Rootless Docker socket is unavailable for slot ${SLOT_NUMBER}." >&2
    exit 70
  }
  timeout --foreground 600 \
    runuser -u "atenea-slot${SLOT_NUMBER}" -- env \
    HOME="${home}" \
    USER="atenea-slot${SLOT_NUMBER}" \
    LOGNAME="atenea-slot${SLOT_NUMBER}" \
    XDG_RUNTIME_DIR="${runtime_dir}" \
    DBUS_SESSION_BUS_ADDRESS="unix:path=${runtime_dir}/bus" \
    DOCKER_HOST="unix://${socket}" \
    PATH="/usr/bin:/usr/local/bin:/bin" \
    docker "$@"
}

plan() {
  printf 'lock_version=%s\n' "${ATENEA_TOOLCHAIN_LOCK_VERSION}"
  printf 'platform=%s:%s:%s\n' \
    "${ATENEA_TOOLCHAIN_OS_ID}" \
    "${ATENEA_TOOLCHAIN_OS_CODENAME}" \
    "${ATENEA_TOOLCHAIN_ARCH}"
  printf 'host_package=%s\n' "${ATENEA_HOST_PACKAGE_PINS[@]}"
  printf 'docker=%s\n' "${ATENEA_DOCKER_VERSION}"
  printf 'containerd=%s\n' "${ATENEA_CONTAINERD_VERSION}"
  printf 'buildx=%s\n' "${ATENEA_BUILDX_VERSION}"
  printf 'compose=%s\n' "${ATENEA_COMPOSE_VERSION}"
  printf 'image=%s\n' "${ATENEA_TOOLCHAIN_IMAGES[@]}"
  printf 'playwright_module_version=%s\n' "${ATENEA_PLAYWRIGHT_MODULE_VERSION}"
  printf 'playwright_module_relative_root=%s\n' \
    "${ATENEA_PLAYWRIGHT_MODULE_RELATIVE_ROOT}"
  printf 'playwright_module_tree_sha256=%s\n' \
    "${ATENEA_PLAYWRIGHT_MODULE_TREE_SHA256}"
}

install_host() {
  require_root
  verify_platform
  export DEBIAN_FRONTEND=noninteractive
  export NEEDRESTART_MODE=l
  apt-get update
  apt-get install -y --allow-downgrades "${ATENEA_HOST_PACKAGE_PINS[@]}"
  verify_host
}

verify_package() {
  local pin="$1" package expected actual
  package="${pin%%=*}"
  expected="${pin#*=}"
  actual="$(dpkg-query -W -f='${Version}' "${package}" 2>/dev/null || true)"
  [[ "${actual}" == "${expected}" ]] || {
    echo "Package mismatch: ${package} expected=${expected} actual=${actual:-missing}" >&2
    return 1
  }
}

verify_host() {
  require_root
  verify_platform

  local failures=0 pin
  for pin in "${ATENEA_HOST_PACKAGE_PINS[@]}"; do
    verify_package "${pin}" || failures=$((failures + 1))
  done

  verify_package "docker-ce=${ATENEA_DOCKER_VERSION}" || failures=$((failures + 1))
  verify_package "docker-ce-cli=${ATENEA_DOCKER_VERSION}" || failures=$((failures + 1))
  verify_package "docker-ce-rootless-extras=${ATENEA_DOCKER_VERSION}" || failures=$((failures + 1))
  verify_package "containerd.io=${ATENEA_CONTAINERD_VERSION}" || failures=$((failures + 1))
  verify_package "docker-buildx-plugin=${ATENEA_BUILDX_VERSION}" || failures=$((failures + 1))
  verify_package "docker-compose-plugin=${ATENEA_COMPOSE_VERSION}" || failures=$((failures + 1))

  for unit in docker.service docker.socket containerd.service; do
    if systemctl is-active --quiet "${unit}"; then
      echo "Privileged runtime must remain inactive: ${unit}" >&2
      failures=$((failures + 1))
    fi
  done

  [[ "${failures}" -eq 0 ]] || exit 1
  echo "Host toolchain prerequisites match lock v${ATENEA_TOOLCHAIN_LOCK_VERSION}."
}

install_images() {
  require_root
  verify_platform
  validate_slot
  local image
  for image in "${ATENEA_TOOLCHAIN_IMAGES[@]}"; do
    slot_docker pull "${image}"
  done
  install_playwright_module
  verify_slot
}

playwright_source_root() {
  printf '%s/playwright-module-v1\n' "${SCRIPT_DIR}"
}

playwright_module_root() {
  printf '%s/%s\n' \
    "$(slot_home)" "${ATENEA_PLAYWRIGHT_MODULE_RELATIVE_ROOT}"
}

verify_playwright_source() {
  local source_root
  source_root="$(playwright_source_root)"
  [[ -d "${source_root}" && ! -L "${source_root}" ]]
  [[ "$(sha256sum "${source_root}/package.json" | cut -d' ' -f1)" == \
    "${ATENEA_PLAYWRIGHT_PACKAGE_SHA256}" ]]
  [[ "$(sha256sum "${source_root}/package-lock.json" | cut -d' ' -f1)" == \
    "${ATENEA_PLAYWRIGHT_PACKAGE_LOCK_SHA256}" ]]
}

playwright_module_tree_sha256() {
  local root="$1"
  (
    cd "${root}"
    find package.json package-lock.json node_modules \
      \( -type f -o -type l \) -print0 |
      LC_ALL=C sort -z |
      while IFS= read -r -d '' path; do
        if [[ -L "${path}" ]]; then
          printf 'link %s %s\n' "${path}" "$(readlink "${path}")"
        else
          printf 'file %s %s\n' \
            "${path}" "$(sha256sum "${path}" | cut -d' ' -f1)"
        fi
      done
  ) | sha256sum | cut -d' ' -f1
}

verify_playwright_module() {
  local module_root
  module_root="$(playwright_module_root)"
  verify_playwright_source
  [[ -d "${module_root}/node_modules/playwright" &&
      ! -L "${module_root}" ]]
  [[ "$(playwright_module_tree_sha256 "${module_root}")" == \
    "${ATENEA_PLAYWRIGHT_MODULE_TREE_SHA256}" ]]
  [[ "$(
    slot_docker run --rm --network none \
      --mount \
      "type=bind,src=${module_root},dst=/opt/atenea-playwright-module-v1,readonly" \
      -e NODE_PATH=/opt/atenea-playwright-module-v1/node_modules \
      "${ATENEA_PLAYWRIGHT_IMAGE}" \
      node -p 'require("playwright/package.json").version'
  )" == "${ATENEA_PLAYWRIGHT_MODULE_VERSION}" ]]
}

install_playwright_module() {
  require_root
  verify_platform
  validate_slot
  verify_playwright_source

  local source_root module_root staging target_next target_previous
  source_root="$(playwright_source_root)"
  module_root="$(playwright_module_root)"
  staging="$(mktemp -d \
    "/tmp/atenea-playwright-module-v1.slot${SLOT_NUMBER}.XXXXXX")"
  target_next="${module_root}.next"
  target_previous="${module_root}.previous"
  chown "atenea-slot${SLOT_NUMBER}:atenea-slot${SLOT_NUMBER}" "${staging}"
  install -o "atenea-slot${SLOT_NUMBER}" -g "atenea-slot${SLOT_NUMBER}" \
    -m 0600 "${source_root}/package.json" "${source_root}/package-lock.json" \
    "${staging}/"

  if ! slot_docker run --rm \
      --name "atenea-toolchain-playwright-module-v1-slot${SLOT_NUMBER}" \
      --label com.atenea.toolchain=playwright-module-v1 \
      --network bridge \
      --mount "type=bind,src=${staging},dst=/work" \
      --workdir /work \
      "${ATENEA_NODE_IMAGE}" \
      npm ci --omit=dev --ignore-scripts --no-audit --no-fund; then
    rm -rf -- "${staging}"
    return 1
  fi
  [[ "$(playwright_module_tree_sha256 "${staging}")" == \
    "${ATENEA_PLAYWRIGHT_MODULE_TREE_SHA256}" ]] || {
      rm -rf -- "${staging}"
      echo "Installed Playwright module tree does not match the lock." >&2
      return 1
    }

  rm -rf -- "${target_next}" "${target_previous}"
  install -d -o "atenea-slot${SLOT_NUMBER}" -g "atenea-slot${SLOT_NUMBER}" \
    -m 0750 "$(dirname "${target_next}")"
  cp -a -- "${staging}" "${target_next}"
  chown -R "atenea-slot${SLOT_NUMBER}:atenea-slot${SLOT_NUMBER}" \
    "${target_next}"
  find "${target_next}" -type d -exec chmod 0755 {} +
  find "${target_next}" -type f -exec chmod 0644 {} +
  if [[ -e "${module_root}" ]]; then
    mv -- "${module_root}" "${target_previous}"
  fi
  mv -- "${target_next}" "${module_root}"
  rm -rf -- "${target_previous}" "${staging}"
  verify_playwright_module
}

assert_image_present() {
  local image="$1"
  slot_docker image inspect "${image}" >/dev/null
}

verify_slot() {
  require_root
  verify_platform
  validate_slot

  local image
  for image in "${ATENEA_TOOLCHAIN_IMAGES[@]}"; do
    assert_image_present "${image}" || {
      echo "Pinned image is missing from slot ${SLOT_NUMBER}: ${image}" >&2
      exit 1
    }
  done

  [[ "$(slot_docker run --rm --network none "${ATENEA_NODE_IMAGE}" node --version)" == "v22.16.0" ]]
  slot_docker run --rm --network none "${ATENEA_MAVEN_JAVA21_IMAGE}" \
    mvn --version | grep -F 'Apache Maven 3.9.9' >/dev/null
  slot_docker run --rm --network none "${ATENEA_MAVEN_JAVA21_IMAGE}" \
    java -version 2>&1 | grep -F 'version "21' >/dev/null
  slot_docker run --rm --network none "${ATENEA_TOMCAT_JAVA8_IMAGE}" \
    java -version 2>&1 | grep -F 'version "1.8.0_' >/dev/null
  slot_docker run --rm --network none \
    --entrypoint "${ATENEA_PLAYWRIGHT_CHROMIUM_PATH}" \
    "${ATENEA_PLAYWRIGHT_IMAGE}" --version |
    grep -F "${ATENEA_PLAYWRIGHT_CHROMIUM_VERSION}" >/dev/null
  verify_playwright_module

  echo "Slot ${SLOT_NUMBER} toolchains match lock v${ATENEA_TOOLCHAIN_LOCK_VERSION}."
}

case "${ACTION}" in
  plan)
    [[ "$#" -eq 1 ]] || usage
    plan
    ;;
  install-host)
    [[ "$#" -eq 1 ]] || usage
    install_host
    ;;
  install-images)
    [[ "$#" -eq 2 ]] || usage
    install_images
    ;;
  install-playwright-module)
    [[ "$#" -eq 2 ]] || usage
    install_playwright_module
    ;;
  verify-host)
    [[ "$#" -eq 1 ]] || usage
    verify_host
    ;;
  verify-slot)
    [[ "$#" -eq 2 ]] || usage
    verify_slot
    ;;
  *)
    usage
    ;;
esac
