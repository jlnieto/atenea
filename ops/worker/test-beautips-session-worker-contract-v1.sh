#!/usr/bin/env bash

set -Eeuo pipefail
umask 0077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SOURCE="${ATENEA_BEAUTIPS_SOURCE:-/home/jose/IdeaProjects/beautips}"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

for command in git jq python3 sha256sum; do
  command -v "${command}" >/dev/null ||
    fail "required command is unavailable: ${command}"
done

[[ "$(git -C "${SOURCE}" rev-parse HEAD)" == \
  e9e0b3c319c518363d4135f5378ebbddced96dfb ]] ||
  fail 'Beautips source commit differs'
[[ -z "$(git -C "${SOURCE}" status --short)" ]] ||
  fail 'Beautips source is not clean'
[[ "$(sha256sum "${SOURCE}/ops/atenea-runtime.json" | cut -d' ' -f1)" == \
  365f1c66c51c9018c2c6f48deddbaa619b4588cae2dd463dcd916cde884e2e82 ]] ||
  fail 'Beautips manifest hash differs'
[[ "$(sha256sum "${SOURCE}/ops/docker-compose.atenea.yml" | cut -d' ' -f1)" == \
  840e64166e8e1ddaefb74d11763fe150e6539074bb02c3173e2175a446555941 ]] ||
  fail 'Beautips managed Compose hash differs'

python3 "${SCRIPT_DIR}/test-beautips-manifest-ownership-cleanup-v1.py"
python3 "${SCRIPT_DIR}/test-beautips-operation-mediator-v1.py"
python3 "${SCRIPT_DIR}/test-beautips-project-codex-runner-v1.py"
python3 "${SCRIPT_DIR}/test-beautips-secret-boundary-v1.py"
"${SCRIPT_DIR}/test-install-beautips-project-v1.sh"

# The Beautips adapter deliberately reuses these accepted generic worker
# semantics. Selection integration remains disabled until OpenSpec phase 3.
python3 "${SCRIPT_DIR}/test-agent-run-worker-v1.py" \
  ProjectWorkerStateTest.test_exact_project_dispatch_is_idempotent_and_preserves_thread \
  ProjectWorkerStateTest.test_cancel_terminates_only_exact_project_process \
  ProjectWorkerStateTest.test_restart_reconciliation_does_not_duplicate_uncertain_turn

printf 'Beautips session worker contract v1 tests passed.\n'
