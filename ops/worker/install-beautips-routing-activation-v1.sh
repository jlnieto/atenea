#!/usr/bin/env bash

set -Eeuo pipefail
umask 0077

ACTION="${1:-}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROGRAM=/usr/local/libexec/atenea/beautips-workspace-activation-v1.sh
SUDOERS=/etc/sudoers.d/93-atenea-beautips-routing-activation-v1
WORKER_BUNDLE=/srv/atenea/worker/workspace-v1/ops/worker
DEPENDENCIES=(
  session-workspace-v1.sh
  runtime-admission-v1.sh
  session-runtime-allocation-v1.sh
)

fail() {
  printf 'BEAUTIPS_ROUTING_ACTIVATION_INSTALL_REJECTED: %s\n' "$1" >&2
  exit 65
}

[[ "${ACTION}" =~ ^(plan|apply|verify|rollback)$ && "$#" -eq 1 ]] ||
  fail 'usage: plan|apply|verify|rollback'

source_hash() {
  sha256sum "${SCRIPT_DIR}/beautips-workspace-activation-v1.sh" | cut -d' ' -f1
}

sudoers_content() {
  printf 'atenea-worker ALL=(root) NOPASSWD: %s ensure *\n' "${PROGRAM}"
}

verify() {
  [[ "${EUID}" -eq 0 ]] || fail 'verification requires root'
  [[ -f "${PROGRAM}" && ! -L "${PROGRAM}" &&
      "$(stat -c %U:%G:%a "${PROGRAM}")" == root:root:755 &&
      "$(sha256sum "${PROGRAM}" | cut -d' ' -f1)" == "$(source_hash)" ]] ||
    fail 'installed mediator differs'
  [[ -f "${SUDOERS}" && ! -L "${SUDOERS}" &&
      "$(stat -c %U:%G:%a "${SUDOERS}")" == root:root:440 &&
      "$(cat "${SUDOERS}")" == "$(sudoers_content)" ]] ||
    fail 'sudoers boundary differs'
  visudo -cf "${SUDOERS}" >/dev/null ||
    fail 'sudoers boundary is invalid'
  for dependency in "${DEPENDENCIES[@]}"; do
    installed="${WORKER_BUNDLE}/${dependency}"
    source="${SCRIPT_DIR}/${dependency}"
    [[ -f "${installed}" && ! -L "${installed}" &&
        "$(stat -c %U:%G:%a "${installed}")" == atenea-worker:atenea:750 &&
        "$(sha256sum "${installed}" | cut -d' ' -f1)" == \
          "$(sha256sum "${source}" | cut -d' ' -f1)" ]] ||
      fail "reviewed dependency differs: ${dependency}"
  done
  jq -cn --arg program "${PROGRAM}" '{
    state: "verified",
    program: $program,
    projectId: "beautips",
    arbitraryAuthority: false
  }'
}

case "${ACTION}" in
  plan)
    jq -cn --arg program "${PROGRAM}" --arg sudoers "${SUDOERS}" '{
      action: "apply",
      program: $program,
      sudoers: $sudoers,
      defaultRoutingChange: false,
      serviceRestart: false
    }'
    ;;
  apply)
    [[ "${EUID}" -eq 0 ]] || fail 'installation requires root'
    install -d -o root -g root -m 0755 "$(dirname -- "${PROGRAM}")"
    install -d -o atenea-worker -g atenea -m 0750 "${WORKER_BUNDLE}"
    for dependency in "${DEPENDENCIES[@]}"; do
      install -o atenea-worker -g atenea -m 0750 \
        "${SCRIPT_DIR}/${dependency}" "${WORKER_BUNDLE}/${dependency}"
    done
    install -o root -g root -m 0755 \
      "${SCRIPT_DIR}/beautips-workspace-activation-v1.sh" "${PROGRAM}"
    temporary="$(mktemp /etc/sudoers.d/.beautips-routing.XXXXXX)"
    sudoers_content >"${temporary}"
    chown root:root "${temporary}"
    chmod 0440 "${temporary}"
    visudo -cf "${temporary}" >/dev/null
    mv -f "${temporary}" "${SUDOERS}"
    verify
    ;;
  verify)
    verify
    ;;
  rollback)
    [[ "${EUID}" -eq 0 ]] || fail 'rollback requires root'
    verify >/dev/null
    rm -f -- "${SUDOERS}" "${PROGRAM}"
    jq -cn '{state: "rolled-back", routingChanged: false}'
    ;;
esac
