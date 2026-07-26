#!/usr/bin/env bash

set -Eeuo pipefail
umask 0077

SESSION_ID=""
ALLOCATION=""
MANIFEST=""
DOCKER_HOST_VALUE=""
JSON_MODE=false
ENGINE_LABEL="atenea-runtime-engine-v1"

fail() {
  printf '%s: %s\n' "$1" "$2" >&2
  exit 65
}

usage() {
  cat >&2 <<EOF
Usage:
  $0 cleanup --session <uuid> --allocation <path> --manifest <path> \
    --docker-host <unix-socket> [--json]
EOF
  exit 64
}

[[ "${1:-}" == "cleanup" ]] || usage
shift
while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --session) SESSION_ID="${2:-}"; shift 2 ;;
    --allocation) ALLOCATION="${2:-}"; shift 2 ;;
    --manifest) MANIFEST="${2:-}"; shift 2 ;;
    --docker-host) DOCKER_HOST_VALUE="${2:-}"; shift 2 ;;
    --json) JSON_MODE=true; shift ;;
    *) usage ;;
  esac
done

[[ "${SESSION_ID}" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] ||
  fail SESSION_REQUIRED "Cleanup requires one canonical synthetic WorkSession UUID."
[[ "${ALLOCATION}" == /tmp/* && "${MANIFEST}" == /tmp/* &&
    "${ALLOCATION}" != *".."* && "${MANIFEST}" != *".."* ]] ||
  fail RUNTIME_OWNERSHIP_CONFLICT "Cleanup inputs must be explicit synthetic paths beneath /tmp."
[[ -f "${ALLOCATION}" && ! -L "${ALLOCATION}" &&
    -f "${MANIFEST}" && ! -L "${MANIFEST}" ]] ||
  fail RUNTIME_OWNERSHIP_CONFLICT "Cleanup inputs are missing or unsafe."
[[ "${DOCKER_HOST_VALUE}" == unix:///tmp/* ||
    "${DOCKER_HOST_VALUE}" =~ ^unix:///run/atenea-runtime/slot[1-4]/docker\.sock$ ]] ||
  fail RUNTIME_OWNERSHIP_CONFLICT "Cleanup Docker host is not a synthetic socket or assigned slot proxy."

for command in docker jq realpath stat; do
  command -v "${command}" >/dev/null ||
    fail OPERATION_FAILED "A fixed cleanup prerequisite is unavailable."
done

RUNTIME_ID="ws-${SESSION_ID//-/}"
jq -e \
  --arg session "${SESSION_ID}" \
  --arg runtime "${RUNTIME_ID}" '
    .schemaVersion == 1 and .state == "allocated" and
    .sessionId == $session and .runtimeId == $runtime and
    (.slot | test("^slot[1-4]$")) and
    (.projectId == "dummy-compose" or .projectId == "dummy-tomcat") and
    .runtimeNames.composeProject == ($runtime + "-compose") and
    .runtimeNames.network == ($runtime + "-network") and
    .runtimeNames.volumePrefix == ($runtime + "-volume") and
    (.worktreePath | startswith("/tmp/")) and
    (.runtimeRoot | startswith("/tmp/")) and
    (.logsPath | startswith("/tmp/")) and
    (.artifactsRoot | startswith("/tmp/"))
  ' "${ALLOCATION}" >/dev/null ||
  fail RUNTIME_OWNERSHIP_CONFLICT "Allocation identity is incompatible with cleanup v1."

PROJECT="$(jq -r '.projectId' "${ALLOCATION}")"
WORKTREE="$(jq -r '.worktreePath' "${ALLOCATION}")"
RUNTIME_ROOT="$(jq -r '.runtimeRoot' "${ALLOCATION}")"
LOGS_PATH="$(jq -r '.logsPath' "${ALLOCATION}")"
ARTIFACTS_ROOT="$(jq -r '.artifactsRoot' "${ALLOCATION}")"
NETWORK="$(jq -r '.runtimeNames.network' "${ALLOCATION}")"
KIND="$(jq -r '.runtime.kind' "${MANIFEST}")"
[[ "$(realpath -e "${MANIFEST}")" == "$(realpath -e "${WORKTREE}")/runtime.json" &&
    "$(jq -r '.project.id' "${MANIFEST}")" == "${PROJECT}" &&
    (("${PROJECT}" == "dummy-compose" && "${KIND}" == "compose") ||
     ("${PROJECT}" == "dummy-tomcat" && "${KIND}" == "tomcat")) ]] ||
  fail MANIFEST_INVALID "Manifest does not belong to the cleanup allocation."

CONTAINER="${RUNTIME_ID}-$([[ "${KIND}" == compose ]] && printf app || printf tomcat)"
IMAGE="${RUNTIME_ID}-fixture-${KIND}:task-4.3"
ENGINE_ROOT="${RUNTIME_ROOT}/engine-v1"
LOCK_PATH="${RUNTIME_ROOT}/engine-v1.lock"

docker_cmd() {
  DOCKER_HOST="${DOCKER_HOST_VALUE}" timeout --foreground 120 docker "$@"
}

labels_for() {
  local kind="$1" object="$2"
  if [[ "${kind}" == network ]]; then
    docker_cmd network inspect --format \
      '{{ index .Labels "com.atenea.engine" }} {{ index .Labels "com.atenea.session" }} {{ index .Labels "com.atenea.runtime" }}' \
      "${object}" 2>/dev/null
  else
    docker_cmd "${kind}" inspect --format \
      '{{ index .Config.Labels "com.atenea.engine" }} {{ index .Config.Labels "com.atenea.session" }} {{ index .Config.Labels "com.atenea.runtime" }}' \
      "${object}" 2>/dev/null
  fi
}

exists() {
  docker_cmd "$1" inspect "$2" >/dev/null 2>&1
}

assert_owned_if_present() {
  local kind="$1" object="$2" observed
  exists "${kind}" "${object}" || return 0
  observed="$(labels_for "${kind}" "${object}" || true)"
  [[ "${observed}" == "${ENGINE_LABEL} ${SESSION_ID} ${RUNTIME_ID}" ]] ||
    fail RUNTIME_OWNERSHIP_CONFLICT "Cleanup rejected an unlabelled, foreign or ambiguously owned ${kind}."
}

# Validate every existing target before deleting the first one.
assert_owned_if_present container "${CONTAINER}"
assert_owned_if_present network "${NETWORK}"
assert_owned_if_present image "${IMAGE}"

removed_container=false
removed_network=false
removed_image=false
removed_temporary=false
if exists container "${CONTAINER}"; then
  docker_cmd rm --force "${CONTAINER}" >/dev/null
  removed_container=true
fi
if exists network "${NETWORK}"; then
  docker_cmd network rm "${NETWORK}" >/dev/null
  removed_network=true
fi
if exists image "${IMAGE}"; then
  docker_cmd image rm "${IMAGE}" >/dev/null
  removed_image=true
fi

if [[ -e "${ENGINE_ROOT}" || -L "${ENGINE_ROOT}" ]]; then
  [[ -d "${ENGINE_ROOT}" && ! -L "${ENGINE_ROOT}" &&
      -f "${ENGINE_ROOT}/.owner-v1" && ! -L "${ENGINE_ROOT}/.owner-v1" &&
      "$(cat "${ENGINE_ROOT}/.owner-v1")" == "${SESSION_ID} ${RUNTIME_ID}" ]] ||
    fail RUNTIME_OWNERSHIP_CONFLICT "Cleanup rejected an ambiguous engine temporary root."
  rm -rf -- "${ENGINE_ROOT}"
  removed_temporary=true
fi
if [[ -e "${LOCK_PATH}" || -L "${LOCK_PATH}" ]]; then
  [[ -f "${LOCK_PATH}" && ! -L "${LOCK_PATH}" ]] ||
    fail RUNTIME_OWNERSHIP_CONFLICT "Cleanup rejected an unsafe runtime lock."
  rm -f -- "${LOCK_PATH}"
  removed_temporary=true
fi

# These are retained evidence roots and are never cleanup targets.
[[ -d "${WORKTREE}" && -d "${LOGS_PATH}" && -d "${ARTIFACTS_ROOT}" ]] ||
  fail RECONCILIATION_REQUIRED "Cleanup detected missing retained session evidence."

if [[ "${JSON_MODE}" == true ]]; then
  jq -cn \
    --arg session "${SESSION_ID}" \
    --arg runtime "${RUNTIME_ID}" \
    --argjson container "${removed_container}" \
    --argjson network "${removed_network}" \
    --argjson image "${removed_image}" \
    --argjson temporary "${removed_temporary}" '{
      schemaVersion: 1,
      operation: "cleanup",
      state: "clean",
      sessionId: $session,
      runtimeId: $runtime,
      removed: {
        container: $container,
        network: $network,
        image: $image,
        temporary: $temporary
      },
      retained: ["workspace-record", "allocation-record", "worktree", "logs", "artifacts"]
    }'
else
  printf 'Synthetic cleanup complete for %s.\n' "${SESSION_ID}"
fi
