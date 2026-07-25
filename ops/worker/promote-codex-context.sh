#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
ALLOWLIST="${SCRIPT_DIR}/codex-context-allowlist-v1.txt"
LOCK_FILE="${SCRIPT_DIR}/codex-context-lock-v1.txt"
ADMIN_USER="${ATENEA_WORKER_ADMIN_USER:-jose}"
CONTEXT_VERSION="remote-codex-admin-v1"
BACKUP_ROOT="/var/backups/atenea-worker-runtime"
ACTION="${1:-plan}"

fail() {
  echo "Context promotion blocked: $*" >&2
  exit 65
}

contains_credential_material() {
  grep -Eiq -- \
    '-----BEGIN ([A-Z ]+ )?PRIVATE KEY-----|(^|[^A-Za-z0-9])(sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16})|^[[:space:]]*(api[_-]?key|access[_-]?token|refresh[_-]?token|password|client[_-]?secret|secret)[[:space:]]*=' \
    "$1"
}

[[ "${ACTION}" == "plan" || "${ACTION}" == "apply" ]] ||
  fail "usage: $0 [plan|apply]"
[[ -f "${ALLOWLIST}" && ! -L "${ALLOWLIST}" ]] ||
  fail "missing regular allowlist: ${ALLOWLIST}"
[[ -f "${LOCK_FILE}" && ! -L "${LOCK_FILE}" ]] ||
  fail "missing regular context lock: ${LOCK_FILE}"

ADMIN_HOME="$(getent passwd "${ADMIN_USER}" | cut -d: -f6)"
[[ -n "${ADMIN_HOME}" && -d "${ADMIN_HOME}" ]] ||
  fail "administrative user or home not found: ${ADMIN_USER}"
CODEX_HOME="${ADMIN_HOME}/.codex"

stage="$(mktemp -d)"
trap 'rm -rf -- "${stage}"' EXIT
install_root="${stage}/install"
manifest="${stage}/context-manifest.json"
hash_input="${stage}/aggregate-hashes"
entries="${stage}/manifest-entries"
mkdir -p "${install_root}"
: > "${hash_input}"
: > "${entries}"

source_revision="$(
  sed -n 's/^# source_revision|//p' "${LOCK_FILE}"
)"
[[ "${source_revision}" =~ ^[0-9a-f]{40}$ ]] ||
  fail "context lock has no valid source revision"
entry_count=0

while IFS='|' read -r source target mode kind; do
  [[ -n "${source}" && "${source:0:1}" != "#" ]] || continue

  case "${kind}:${target}:${mode}" in
    configuration:config.toml:0600|instructions:AGENTS.md:0644)
      ;;
    skill:skills/*/SKILL.md:0644)
      ;;
    *)
      fail "unsafe allowlist entry: ${source}|${target}|${mode}|${kind}"
      ;;
  esac

  [[ "${source}" != /* && "${source}" != *".."* ]] ||
    fail "source escapes repository: ${source}"
  [[ "${target}" != /* && "${target}" != *".."* ]] ||
    fail "target escapes Codex home: ${target}"

  source_path="${REPO_ROOT}/${source}"
  [[ -f "${source_path}" && ! -L "${source_path}" ]] ||
    fail "source is not a regular file: ${source}"
  locked_hash="$(
    awk -F'|' -v source="${source}" \
      '$2 == source { print $1 }' "${LOCK_FILE}"
  )"
  [[ "${locked_hash}" =~ ^[0-9a-f]{64}$ ]] ||
    fail "source is absent or duplicated in context lock: ${source}"

  if contains_credential_material "${source_path}"; then
    fail "credential-like material detected in allowlisted source: ${source}"
  fi

  destination="${install_root}/${target}"
  mkdir -p "$(dirname -- "${destination}")"
  install -m "${mode}" "${source_path}" "${destination}"
  hash="$(sha256sum "${source_path}" | cut -d' ' -f1)"
  [[ "${hash}" == "${locked_hash}" ]] ||
    fail "source differs from locked revision ${source_revision}: ${source}"
  if [[ -d "${REPO_ROOT}/.git" || -f "${REPO_ROOT}/.git" ]]; then
    git -C "${REPO_ROOT}" cat-file -e "${source_revision}:${source}" ||
      fail "source is absent from revision ${source_revision}: ${source}"
    revision_hash="$(
      git -C "${REPO_ROOT}" show "${source_revision}:${source}" | sha256sum |
        cut -d' ' -f1
    )"
    [[ "${revision_hash}" == "${hash}" ]] ||
      fail "locked source does not match revision ${source_revision}: ${source}"
  fi
  printf '%s\t%s\n' "${target}" "${hash}" >> "${hash_input}"
  printf '%s|%s|%s|%s|%s\n' \
    "${source}" "${target}" "${mode}" "${kind}" "${hash}" >> "${entries}"
  entry_count=$((entry_count + 1))
done < "${ALLOWLIST}"

[[ "${entry_count}" -gt 0 ]] || fail "allowlist is empty"
locked_entry_count="$(grep -Ec '^[0-9a-f]{64}\|' "${LOCK_FILE}")"
[[ "${locked_entry_count}" -eq "${entry_count}" ]] ||
  fail "context lock and allowlist entry counts differ"
aggregate_hash="$(sha256sum "${hash_input}" | cut -d' ' -f1)"

{
  printf '{\n'
  printf '  "schema_version": 1,\n'
  printf '  "context_version": "%s",\n' "${CONTEXT_VERSION}"
  printf '  "source_revision": "%s",\n' "${source_revision}"
  printf '  "aggregate_sha256": "%s",\n' "${aggregate_hash}"
  printf '  "files": [\n'
  index=0
  while IFS='|' read -r source target mode kind hash; do
    index=$((index + 1))
    comma=","
    [[ "${index}" -eq "${entry_count}" ]] && comma=""
    printf '    {"source":"%s","target":"%s","mode":"%s","kind":"%s","sha256":"%s"}%s\n' \
      "${source}" "${target}" "${mode}" "${kind}" "${hash}" "${comma}"
  done < "${entries}"
  printf '  ],\n'
  printf '  "explicitly_excluded": ["auth.json","history.json","history.jsonl","sessions","log","logs","cache","shell_snapshots","state databases","SSH keys","project secrets"]\n'
  printf '}\n'
} > "${manifest}"

if [[ "${ACTION}" == "plan" ]]; then
  cat "${manifest}"
  exit 0
fi

[[ "${EUID}" -eq 0 ]] || fail "apply must run as root"

if [[ -d "${CODEX_HOME}/skills" ]]; then
  while IFS= read -r existing; do
    relative="${existing#"${CODEX_HOME}/"}"
    allowed=false
    while IFS='|' read -r _ target _ kind; do
      [[ "${kind}" == "skill" && "${relative}" == "${target}" ]] && allowed=true
    done < "${ALLOWLIST}"
    "${allowed}" || fail "unexpected custom skill file exists: ${relative}"
  done < <(
    find "${CODEX_HOME}/skills" -mindepth 2 -type f \
      ! -path "${CODEX_HOME}/skills/.system/*" -print
  )
fi

changed=false
while IFS='|' read -r _ target _ _; do
  [[ -f "${CODEX_HOME}/${target}" ]] &&
    cmp -s "${install_root}/${target}" "${CODEX_HOME}/${target}" ||
    changed=true
done < <(grep -v '^#' "${ALLOWLIST}")
[[ -f "${CODEX_HOME}/context-manifest.json" ]] &&
  cmp -s "${manifest}" "${CODEX_HOME}/context-manifest.json" ||
  changed=true

if ! "${changed}"; then
  echo "Codex context already current: ${CONTEXT_VERSION} ${aggregate_hash}"
  exit 0
fi

run_id="$(date -u +%Y%m%dT%H%M%SZ)"
snapshot="${BACKUP_ROOT}/${run_id}-codex-context"
install -d -m 0700 "${BACKUP_ROOT}" "${snapshot}"

while IFS='|' read -r _ target mode _; do
  destination="${CODEX_HOME}/${target}"
  if [[ -f "${destination}" ]]; then
    contains_credential_material "${destination}" &&
      fail "refusing to back up credential-like target: ${target}"
    install -D -m "${mode}" "${destination}" "${snapshot}/${target}"
  fi
  install -d -m 0700 -o "${ADMIN_USER}" -g "${ADMIN_USER}" \
    "$(dirname -- "${destination}")"
  install -m "${mode}" -o "${ADMIN_USER}" -g "${ADMIN_USER}" \
    "${install_root}/${target}" "${destination}"
done < <(grep -v '^#' "${ALLOWLIST}")

install -m 0644 -o "${ADMIN_USER}" -g "${ADMIN_USER}" \
  "${manifest}" "${CODEX_HOME}/context-manifest.json"

while IFS='|' read -r _ target _ _; do
  cmp -s "${install_root}/${target}" "${CODEX_HOME}/${target}" ||
    fail "installed hash verification failed: ${target}"
done < <(grep -v '^#' "${ALLOWLIST}")

echo "Codex context promoted: ${CONTEXT_VERSION} ${aggregate_hash}"
echo "Effective context manifest: ${CODEX_HOME}/context-manifest.json"
echo "Rollback snapshot: ${snapshot}"
