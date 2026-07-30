#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TEST_ROOT="$(mktemp -d /tmp/beautips-workspace-activation.XXXXXX)"
trap 'rm -rf -- "${TEST_ROOT}"' EXIT

LIBEXEC="${TEST_ROOT}/usr/local/libexec/atenea"
CONFIG_ROOT="${TEST_ROOT}/etc/atenea-worker"
mkdir -p "${LIBEXEC}" "${CONFIG_ROOT}"
cp "${SCRIPT_DIR}/beautips-workspace-activation-v1.sh" "${LIBEXEC}/"
chmod 0755 "${LIBEXEC}/beautips-workspace-activation-v1.sh"

MANIFEST_SOURCE=/home/jose/IdeaProjects/beautips/ops/atenea-runtime.json
[[ -f "${MANIFEST_SOURCE}" ]] || {
  printf 'accepted Beautips manifest fixture is unavailable\n' >&2
  exit 1
}
[[ "$(sha256sum "${MANIFEST_SOURCE}" | cut -d' ' -f1)" == \
  365f1c66c51c9018c2c6f48deddbaa619b4588cae2dd463dcd916cde884e2e82 ]] || {
  printf 'accepted Beautips manifest fixture differs\n' >&2
  exit 1
}

cat >"${LIBEXEC}/session-workspace-v1.sh" <<'SH'
#!/usr/bin/env bash
set -eu
root="${ATENEA_BEAUTIPS_ACTIVATION_TEST_ROOT}"
session="$2"
mkdir -p "${root}/srv/atenea/workspaces/sessions/${session}/beautips/ops"
cp "${FIXTURE_MANIFEST}" \
  "${root}/srv/atenea/workspaces/sessions/${session}/beautips/ops/atenea-runtime.json"
SH

cat >"${LIBEXEC}/runtime-admission-v1.sh" <<'SH'
#!/usr/bin/env bash
set -eu
exit 0
SH

cat >"${LIBEXEC}/session-runtime-allocation-v1.sh" <<'SH'
#!/usr/bin/env bash
set -eu
exit 0
SH

cat >"${LIBEXEC}/install-beautips-project-v1.sh" <<'SH'
#!/usr/bin/env bash
set -eu
config="${ATENEA_BEAUTIPS_ACTIVATION_TEST_ROOT}/etc/atenea-worker/beautips-project-codex-v1.json"
temporary="${config}.new"
case "$1" in
  selection-enable)
    jq '.selectionEnabled = true' "${config}" >"${temporary}"
    ;;
  register)
    jq --arg session "$2" --arg identity "$3" '
      .workspaces = {($identity): {sessionId: $session}}
    ' "${config}" >"${temporary}"
    ;;
  enable)
    jq '.executionEnabled = true' "${config}" >"${temporary}"
    ;;
  *) exit 64 ;;
esac
mv "${temporary}" "${config}"
SH
chmod 0755 "${LIBEXEC}"/*.sh

jq -n '{
  selectionEnabled: false,
  executionEnabled: false,
  workspaces: {}
}' >"${CONFIG_ROOT}/beautips-project-codex-v1.json"

export ATENEA_BEAUTIPS_ACTIVATION_TEST_MODE=1
export ATENEA_BEAUTIPS_ACTIVATION_TEST_ROOT="${TEST_ROOT}"
export FIXTURE_MANIFEST="${MANIFEST_SOURCE}"
SESSION_ID=11111111-1111-4111-8111-111111111111

first="$("${LIBEXEC}/beautips-workspace-activation-v1.sh" \
  ensure "${SESSION_ID}" codex/work-session-91)"
second="$("${LIBEXEC}/beautips-workspace-activation-v1.sh" \
  ensure "${SESSION_ID}" codex/work-session-91)"
[[ "${first}" == "${second}" ]] || {
  printf 'repeated activation response differs\n' >&2
  exit 1
}
jq -e '
  .state == "ready" and
  .projectId == "beautips" and
  .slot == "slot4" and
  .valuesExposed == false
' <<<"${first}" >/dev/null

if "${LIBEXEC}/beautips-workspace-activation-v1.sh" \
  ensure not-a-uuid codex/work-session-91 >/dev/null 2>&1; then
  printf 'foreign session was accepted\n' >&2
  exit 1
fi
if "${LIBEXEC}/beautips-workspace-activation-v1.sh" \
  ensure "${SESSION_ID}" main >/dev/null 2>&1; then
  printf 'foreign branch was accepted\n' >&2
  exit 1
fi

printf 'beautips workspace activation tests passed\n'
