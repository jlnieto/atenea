#!/usr/bin/env bash

set -Eeuo pipefail

fail() {
  printf '%s: %s\n' "$1" "$2" >&2
  exit 65
}

if [[ "${ATENEA_DATABASE_TEST_MODE:-0}" == "1" ]]; then
  program="${ATENEA_DATABASE_WORKER:-}"
  [[ "${program}" == /tmp/* && "${program}" != *".."* &&
      -f "${program}" && ! -L "${program}" && -x "${program}" ]] ||
    fail DATABASE_OWNERSHIP_CONFLICT "synthetic mediator is unsafe"
  exec "${program}" "$@"
fi

[[ "$(id -un)" == "atenea-worker" ]] ||
  fail DATABASE_OWNERSHIP_CONFLICT "database client must run as atenea-worker"
program="/usr/libexec/atenea-database-lifecycle-v1"
[[ -f "${program}" && ! -L "${program}" && -x "${program}" &&
    "$(stat -c %U:%G:%a "${program}")" == "root:root:755" ]] ||
  fail DATABASE_MEDIATOR_UNAVAILABLE "root-owned database mediator is unavailable"
exec /usr/bin/sudo -n "${program}" "$@"
