#!/usr/bin/env bash

set -Eeuo pipefail
umask 0007

ACTION="${1:-}"
SESSION_ID="${2:-}"
SLOT="${3:-}"
MANIFEST_PATH="${4:-}"
TEST_MODE="${ATENEA_RUNTIME_TEST_MODE:-0}"
SERVICE_USER="${ATENEA_WORKER_SERVICE_USER:-atenea-worker}"
PORT_RANGE_START="${ATENEA_RUNTIME_PORT_START:-20000}"
PORT_RANGE_END="${ATENEA_RUNTIME_PORT_END:-29999}"

fail() {
  local code="$1" message="$2" action="$3"
  printf '%s: %s\nNext action: %s\n' "${code}" "${message}" "${action}" >&2
  exit 65
}

usage() {
  cat >&2 <<EOF
Usage:
  $0 ensure <session-uuid> <slot1-slot4> <project-runtime-manifest.json>
EOF
  exit 64
}

[[ "${ACTION}" == "ensure" && "$#" -eq 4 ]] || usage
for command in flock jq realpath sha256sum ss stat; do
  command -v "${command}" >/dev/null ||
    fail "OPERATION_FAILED" "Required command is unavailable: ${command}" \
      "Install the version-pinned worker prerequisites and retry."
done
[[ "${SESSION_ID}" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] ||
  fail "SESSION_REQUIRED" "Session identity is not a canonical lowercase UUID." \
    "Retry with the persisted WorkSession UUID."
[[ "${SLOT}" =~ ^slot[1-4]$ ]] ||
  fail "RUNTIME_OWNERSHIP_CONFLICT" "Runtime slot must be slot1 through slot4." \
    "Use the slot persisted for this WorkSession."
[[ "${PORT_RANGE_START}" =~ ^[0-9]+$ &&
      "${PORT_RANGE_END}" =~ ^[0-9]+$ &&
      "${PORT_RANGE_START}" -ge 1024 &&
      "${PORT_RANGE_END}" -le 65535 &&
      "${PORT_RANGE_START}" -le "${PORT_RANGE_END}" ]] ||
  fail "OPERATION_FAILED" "Runtime port range must be within 1024 through 65535." \
    "Correct the worker runtime port-range configuration and retry."

if [[ "${TEST_MODE}" == "1" ]]; then
  WORKSPACE_ROOT="${ATENEA_WORKSPACE_ROOT:-}"
  ARTIFACT_ROOT="${ATENEA_ARTIFACT_ROOT:-}"
  CACHE_ROOT="${ATENEA_CACHE_ROOT:-}"
  CONTROL_ROOT="${ATENEA_RUNTIME_CONTROL_ROOT:-}"
  for root in \
    "${WORKSPACE_ROOT}" "${ARTIFACT_ROOT}" "${CACHE_ROOT}" "${CONTROL_ROOT}"; do
    [[ "${root}" == /tmp/* && "${root}" != *".."* ]] ||
      fail "OPERATION_FAILED" "Test roots must be explicit paths beneath /tmp." \
        "Set all ATENEA_*_ROOT variables to a fresh temporary directory."
  done
else
  [[ "$(id -un)" == "${SERVICE_USER}" ]] ||
    fail "OPERATION_FAILED" "Runtime allocation must run as ${SERVICE_USER}." \
      "Invoke the versioned helper through the worker service identity."
  WORKSPACE_ROOT="/srv/atenea/workspaces"
  ARTIFACT_ROOT="/srv/atenea/artifacts"
  CACHE_ROOT="/srv/atenea/caches"
  CONTROL_ROOT="/srv/atenea/worker/runtime-allocation-v1"
fi

SESSION_ROOT="${WORKSPACE_ROOT}/sessions/${SESSION_ID}"
WORKSPACE_RECORD="${SESSION_ROOT}/workspace-v1.json"
RUNTIME_RECORD="${SESSION_ROOT}/runtime-allocation-v1.json"
LOCK_PATH="${CONTROL_ROOT}/allocation.lock"
RUNTIME_ID="ws-${SESSION_ID//-/}"
RUNTIME_ROOT="${SESSION_ROOT}/runtime/${RUNTIME_ID}"
LOGS_PATH="${ARTIFACT_ROOT}/sessions/${SESSION_ID}/runtime/logs"
ARTIFACTS_ROOT="${ARTIFACT_ROOT}/sessions/${SESSION_ID}/runs"
SESSION_CACHE_ROOT="${CACHE_ROOT}/sessions/${SESSION_ID}"
CACHE_POLICY_PATH="${SESSION_CACHE_ROOT}/cache-policy-v1.json"

assert_safe_root() {
  local root="$1" description="$2"
  if [[ -e "${root}" || -L "${root}" ]]; then
    [[ -d "${root}" && ! -L "${root}" &&
        "$(stat -c %u "${root}")" == "$(id -u)" ]] ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "${description} is unsafe or foreign-owned." \
        "Reconcile the worker-owned path without deleting session data."
  else
    local parent
    parent="$(dirname -- "${root}")"
    [[ -d "${parent}" && ! -L "${parent}" &&
        "$(stat -c %u "${parent}")" == "$(id -u)" ]] ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "${description} has no safe worker-owned parent." \
        "Restore the declared worker filesystem skeleton and retry."
  fi
}

assert_no_symlink_chain() {
  local base="$1" target="$2" description="$3"
  local relative current component
  [[ "${target}" == "${base}/"* ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "${description} escapes its declared root." \
      "Reconcile the deterministic runtime path before retrying."
  relative="${target#"${base}/"}"
  current="${base}"
  IFS='/' read -r -a components <<<"${relative}"
  for component in "${components[@]}"; do
    current="${current}/${component}"
    [[ ! -L "${current}" ]] ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "${description} contains a symbolic-link component." \
        "Inspect the path without following or replacing the link."
  done
}

for root in "${WORKSPACE_ROOT}" "${ARTIFACT_ROOT}" "${CACHE_ROOT}"; do
  assert_safe_root "${root}" "Runtime allocation root"
done
if [[ -e "${CONTROL_ROOT}" || -L "${CONTROL_ROOT}" ]]; then
  assert_safe_root "${CONTROL_ROOT}" "Runtime allocation control root"
else
  assert_safe_root "$(dirname -- "${CONTROL_ROOT}")" \
    "Runtime allocation control parent"
fi
install -d -m 2770 "${CONTROL_ROOT}"
assert_safe_root "${CONTROL_ROOT}" "Runtime allocation control root"
[[ ! -L "${LOCK_PATH}" ]] ||
  fail "RUNTIME_OWNERSHIP_CONFLICT" "The runtime allocation lock is a symbolic link." \
    "Inspect the lock path before retrying."
exec {lock_fd}>"${LOCK_PATH}"
[[ -f "${LOCK_PATH}" && ! -L "${LOCK_PATH}" &&
    "$(stat -c %u "${LOCK_PATH}")" == "$(id -u)" ]] ||
  fail "RUNTIME_OWNERSHIP_CONFLICT" "The runtime allocation lock is not worker-owned." \
    "Inspect the lock path before retrying."
flock -w 30 "${lock_fd}" ||
  fail "RECONCILIATION_REQUIRED" "Timed out waiting for the runtime allocation lock." \
    "Retry after the competing allocation finishes."

assert_no_symlink_chain "${WORKSPACE_ROOT}" "${SESSION_ROOT}" "Session root"
[[ -d "${SESSION_ROOT}" && ! -L "${SESSION_ROOT}" &&
    "$(stat -c %u "${SESSION_ROOT}")" == "$(id -u)" ]] ||
  fail "RUNTIME_OWNERSHIP_CONFLICT" "The session root is missing, unsafe or foreign-owned." \
    "Reconcile task 3.1 without modifying the session worktree."
[[ ! -L "${RUNTIME_RECORD}" ]] ||
  fail "RUNTIME_OWNERSHIP_CONFLICT" "The runtime allocation record is a symbolic link." \
    "Inspect the path without following or replacing the link."
[[ -f "${WORKSPACE_RECORD}" && ! -L "${WORKSPACE_RECORD}" ]] ||
  fail "RUNTIME_OWNERSHIP_CONFLICT" "The session has no regular workspace ownership record." \
    "Provision or reconcile task 3.1 before allocating runtime resources."
[[ "$(stat -c %u "${WORKSPACE_RECORD}")" == "$(id -u)" &&
    "$(stat -c %a "${WORKSPACE_RECORD}")" =~ ^6[04]0$ ]] ||
  fail "RUNTIME_OWNERSHIP_CONFLICT" "The workspace ownership record has unsafe ownership or mode." \
    "Restore the worker-owned task 3.1 record before retrying."
jq -e \
  --arg session "${SESSION_ID}" \
  '.schemaVersion == 1 and .state == "ready" and
   .sessionId == $session and
   (.projectId | type == "string") and
   (.branch | type == "string") and
   (.mirrorPath | type == "string") and
   (.worktreePath | type == "string")' \
  "${WORKSPACE_RECORD}" >/dev/null ||
  fail "RECONCILIATION_REQUIRED" "The workspace is not in a compatible ready state." \
    "Reconcile task 3.1 without resetting, cleaning or switching the worktree."

PROJECT_ID="$(jq -r '.projectId' "${WORKSPACE_RECORD}")"
BRANCH="$(jq -r '.branch' "${WORKSPACE_RECORD}")"
MIRROR_PATH="$(jq -r '.mirrorPath' "${WORKSPACE_RECORD}")"
WORKTREE_PATH="$(jq -r '.worktreePath' "${WORKSPACE_RECORD}")"
[[ "${PROJECT_ID}" =~ ^[a-z][a-z0-9-]{1,62}$ ]] ||
  fail "SESSION_IDENTITY_CONFLICT" "The persisted project identity is invalid." \
    "Repair the task 3.1 ownership record before retrying."
[[ "${WORKTREE_PATH}" == "${SESSION_ROOT}/${PROJECT_ID}" &&
    -d "${WORKTREE_PATH}" && ! -L "${WORKTREE_PATH}" &&
    "$(stat -c %u "${WORKTREE_PATH}")" == "$(id -u)" ]] ||
  fail "RUNTIME_OWNERSHIP_CONFLICT" "The persisted worktree path is missing, unsafe or foreign-owned." \
    "Reconcile task 3.1 without modifying project files."

[[ -f "${MANIFEST_PATH}" && ! -L "${MANIFEST_PATH}" ]] ||
  fail "MANIFEST_INVALID" "Runtime manifest must be a regular file." \
    "Use the reviewed manifest inside the session worktree."
MANIFEST_REAL="$(realpath -e "${MANIFEST_PATH}")"
WORKTREE_REAL="$(realpath -e "${WORKTREE_PATH}")"
[[ "${MANIFEST_REAL}" == "${WORKTREE_REAL}/"* ]] ||
  fail "MANIFEST_INVALID" "Runtime manifest is outside the owned session worktree." \
    "Use the reviewed manifest from this WorkSession."
jq -e \
  --arg project "${PROJECT_ID}" '
    .schemaVersion == 1 and
    .project.id == $project and
    .workloadClass == "normal" and
    (.runtime.internalPorts | type == "array" and length > 0) and
    all(.runtime.internalPorts[];
      (keys | sort) == ["name", "port", "protocol"] and
      (.name | test("^[a-z][a-z0-9-]{1,62}$")) and
      (.port | type == "number" and . >= 1 and . <= 65535 and floor == .) and
      (.protocol == "http" or .protocol == "tcp")) and
    ([.runtime.internalPorts[].name] | length == (unique | length))
  ' "${MANIFEST_REAL}" >/dev/null ||
  fail "MANIFEST_INVALID" "Manifest project, workload or internal ports are incompatible with runtime allocation v1." \
    "Validate the normal-workload manifest and retry; heavy admission is implemented in task 4.4."
WORKLOAD_CLASS="normal"
REQUESTED_PORTS="$(
  jq -cS \
    '[.runtime.internalPorts[] | {
      name: .name,
      internalPort: .port,
      protocol: .protocol
    }] | sort_by(.name)' \
    "${MANIFEST_REAL}"
)"

COMPOSE_PROJECT="${RUNTIME_ID}-compose"
NETWORK_NAME="${RUNTIME_ID}-network"
VOLUME_PREFIX="${RUNTIME_ID}-volume"
PROCESS_UNIT="atenea-${RUNTIME_ID}.service"
TOMCAT_BASE="${RUNTIME_ROOT}/tomcat"

validate_allocation_record() {
  local record="$1"
  [[ -f "${record}" && ! -L "${record}" &&
      "$(stat -c %u "${record}")" == "$(id -u)" &&
      "$(stat -c %a "${record}")" =~ ^6[04]0$ ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "A runtime allocation record is unsafe or foreign-owned." \
      "Inspect the record and reconcile ownership before retrying."
  jq -e \
    --arg workspaceRoot "${WORKSPACE_ROOT}" \
    --arg artifactRoot "${ARTIFACT_ROOT}" \
    --arg cacheRoot "${CACHE_ROOT}" '
    (.sessionId | test("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")) and
    .sessionId as $session |
    ($session | gsub("-"; "")) as $sessionHex |
    ("ws-" + $sessionHex) as $runtime |
    .schemaVersion == 1 and .state == "allocated" and
    (.projectId | test("^[a-z][a-z0-9-]{1,62}$")) and
    .runtimeId == $runtime and
    (.slot | test("^slot[1-4]$")) and
    .workloadClass == "normal" and
    .runtimeNames.composeProject == ($runtime + "-compose") and
    .runtimeNames.network == ($runtime + "-network") and
    .runtimeNames.volumePrefix == ($runtime + "-volume") and
    .runtimeNames.processUnit == ("atenea-" + $runtime + ".service") and
    .runtimeNames.tomcatBase == (
      .worktreePath | sub("/[^/]+$"; "/runtime/" + $runtime + "/tomcat")
    ) and
    .runtimeRoot == (
      .worktreePath | sub("/[^/]+$"; "/runtime/" + $runtime)
    ) and
    .logsPath == (
      $artifactRoot + "/sessions/" + $session + "/runtime/logs"
    ) and
    .artifactsRoot == (
      $artifactRoot + "/sessions/" + $session + "/runs"
    ) and
    .cacheRoot == (
      $cacheRoot + "/sessions/" + $session
    ) and
    (.worktreePath | startswith(
      $workspaceRoot + "/sessions/" + $session + "/"
    )) and
    (.allocatedPorts | type == "array" and length > 0) and
    ([.allocatedPorts[].name] | length == (unique | length)) and
    ([.allocatedPorts[].loopbackPort] | length == (unique | length)) and
    all(.allocatedPorts[];
      (.name | test("^[a-z][a-z0-9-]{1,62}$")) and
      (.internalPort | type == "number" and
        . >= 1 and . <= 65535 and floor == .) and
      (.protocol == "http" or .protocol == "tcp") and
      .bindAddress == "127.0.0.1" and
      (.loopbackPort | type == "number" and floor == .))
  ' "${record}" >/dev/null ||
    fail "RECONCILIATION_REQUIRED" "A persisted runtime allocation record is invalid or incompatible." \
      "Inspect and repair the worker-owned record without deleting retained data."
}

record_matches_request() {
  local record="$1"
  local declared
  declared="$(
    jq -cS \
      '[.allocatedPorts[] | {
        name: .name,
        internalPort: .internalPort,
        protocol: .protocol
      }] | sort_by(.name)' \
      "${record}"
  )"
  jq -e \
    --arg session "${SESSION_ID}" \
    --arg project "${PROJECT_ID}" \
    --arg branch "${BRANCH}" \
    --arg mirror "${MIRROR_PATH}" \
    --arg worktree "${WORKTREE_PATH}" \
    --arg runtime "${RUNTIME_ID}" \
    --arg slot "${SLOT}" \
    --arg runtimeRoot "${RUNTIME_ROOT}" \
    --arg logs "${LOGS_PATH}" \
    --arg artifacts "${ARTIFACTS_ROOT}" \
    --arg cache "${SESSION_CACHE_ROOT}" \
    --arg compose "${COMPOSE_PROJECT}" \
    --arg network "${NETWORK_NAME}" \
    --arg volume "${VOLUME_PREFIX}" \
    --arg unit "${PROCESS_UNIT}" \
    --arg tomcat "${TOMCAT_BASE}" '
      .sessionId == $session and .projectId == $project and
      .branch == $branch and .mirrorPath == $mirror and
      .worktreePath == $worktree and .runtimeId == $runtime and
      .slot == $slot and .workloadClass == "normal" and
      .runtimeRoot == $runtimeRoot and .logsPath == $logs and
      .artifactsRoot == $artifacts and .cacheRoot == $cache and
      .runtimeNames.composeProject == $compose and
      .runtimeNames.network == $network and
      .runtimeNames.volumePrefix == $volume and
      .runtimeNames.processUnit == $unit and
      .runtimeNames.tomcatBase == $tomcat
    ' "${record}" >/dev/null &&
    [[ "${declared}" == "${REQUESTED_PORTS}" ]]
}

declare -A PORT_OWNERS=()
declare -A SLOT_OWNERS=()
while IFS= read -r -d '' record; do
  validate_allocation_record "${record}"
  owner_session="$(jq -r '.sessionId' "${record}")"
  owner_slot="$(jq -r '.slot' "${record}")"
  if [[ -n "${SLOT_OWNERS[${owner_slot}]:-}" &&
        "${SLOT_OWNERS[${owner_slot}]}" != "${owner_session}" ]]; then
    fail "RUNTIME_OWNERSHIP_CONFLICT" "Two persisted sessions claim the same runtime slot." \
      "Reconcile the conflicting allocation records before retrying."
  fi
  SLOT_OWNERS["${owner_slot}"]="${owner_session}"
  while IFS= read -r port; do
    [[ "${port}" -ge "${PORT_RANGE_START}" &&
        "${port}" -le "${PORT_RANGE_END}" ]] ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "A persisted loopback port is outside the configured range." \
        "Restore the allocation range or reconcile the persisted record explicitly."
    if [[ -n "${PORT_OWNERS[${port}]:-}" &&
          "${PORT_OWNERS[${port}]}" != "${owner_session}" ]]; then
      fail "RUNTIME_OWNERSHIP_CONFLICT" "Two persisted sessions claim the same loopback port." \
        "Reconcile the conflicting allocation records before retrying."
    fi
    PORT_OWNERS["${port}"]="${owner_session}"
  done < <(jq -r '.allocatedPorts[].loopbackPort' "${record}")
done < <(
  find "${WORKSPACE_ROOT}/sessions" -mindepth 2 -maxdepth 2 \
    -name runtime-allocation-v1.json -type f -print0 2>/dev/null
)

if [[ -f "${RUNTIME_RECORD}" ]]; then
  validate_allocation_record "${RUNTIME_RECORD}"
  record_matches_request "${RUNTIME_RECORD}" ||
    fail "SESSION_IDENTITY_CONFLICT" "The session already owns a different runtime allocation." \
      "Use the persisted manifest and slot or reconcile the session in Atenea."
  while IFS= read -r port; do
    [[ "${PORT_OWNERS[${port}]:-}" == "${SESSION_ID}" ]] ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "A persisted loopback port has incompatible ownership." \
        "Reconcile the allocation registry before starting a runtime."
  done < <(jq -r '.allocatedPorts[].loopbackPort' "${RUNTIME_RECORD}")
else
  [[ -z "${SLOT_OWNERS[${SLOT}]:-}" ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "The requested runtime slot belongs to another session." \
      "Select a free persisted slot before retrying."

  ALLOCATED_PORTS='[]'
  while IFS=$'\t' read -r port_name internal_port protocol; do
    hash_prefix="$(
      printf '%s' "${SESSION_ID}:${port_name}" |
        sha256sum |
        cut -c1-8
    )"
    range_size="$((PORT_RANGE_END - PORT_RANGE_START + 1))"
    initial_offset="$((16#${hash_prefix} % range_size))"
    selected_port=""
    for ((attempt = 0; attempt < range_size; attempt++)); do
      candidate="$((PORT_RANGE_START + (initial_offset + attempt) % range_size))"
      [[ -z "${PORT_OWNERS[${candidate}]:-}" ]] || continue
      jq -e --argjson candidate "${candidate}" \
        'all(.[]; .loopbackPort != $candidate)' \
        <<<"${ALLOCATED_PORTS}" >/dev/null || continue
      if ss -H -ltn "sport = :${candidate}" | grep -q .; then
        continue
      fi
      selected_port="${candidate}"
      break
    done
    [[ -n "${selected_port}" ]] ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "No collision-free loopback port is available in the configured range." \
        "Free or enlarge the reviewed loopback port range, then retry."
    ALLOCATED_PORTS="$(
      jq -c \
        --arg name "${port_name}" \
        --argjson internal "${internal_port}" \
        --arg protocol "${protocol}" \
        --argjson loopback "${selected_port}" \
        '. + [{
          name: $name,
          internalPort: $internal,
          protocol: $protocol,
          bindAddress: "127.0.0.1",
          loopbackPort: $loopback
        }]' \
        <<<"${ALLOCATED_PORTS}"
    )"
  done < <(
    jq -r '.runtime.internalPorts | sort_by(.name)[] |
      [.name, (.port | tostring), .protocol] | @tsv' "${MANIFEST_REAL}"
  )
fi

for path in \
  "${RUNTIME_ROOT}" "${TOMCAT_BASE}" "${LOGS_PATH}" "${ARTIFACTS_ROOT}" \
  "${SESSION_CACHE_ROOT}" "${SESSION_CACHE_ROOT}/maven" \
  "${SESSION_CACHE_ROOT}/node" "${SESSION_CACHE_ROOT}/oci" \
  "${SESSION_CACHE_ROOT}/browser"; do
  if [[ -e "${path}" || -L "${path}" ]]; then
    [[ -d "${path}" && ! -L "${path}" &&
        "$(stat -c %u "${path}")" == "$(id -u)" ]] ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "A runtime path is unsafe or foreign-owned." \
        "Reconcile ownership without deleting logs, artifacts, caches or source."
  fi
done
assert_no_symlink_chain "${SESSION_ROOT}" "${RUNTIME_ROOT}" "Runtime root"
assert_no_symlink_chain "${SESSION_ROOT}" "${TOMCAT_BASE}" "Tomcat base"
assert_no_symlink_chain "${ARTIFACT_ROOT}" "${LOGS_PATH}" "Log root"
assert_no_symlink_chain "${ARTIFACT_ROOT}" "${ARTIFACTS_ROOT}" "Artifact root"
assert_no_symlink_chain "${CACHE_ROOT}" "${SESSION_CACHE_ROOT}" "Cache root"
for cache_scope in maven node oci browser; do
  assert_no_symlink_chain \
    "${CACHE_ROOT}" "${SESSION_CACHE_ROOT}/${cache_scope}" "Cache scope"
done
install -d -m 2770 \
  "${RUNTIME_ROOT}" "${TOMCAT_BASE}" "${LOGS_PATH}" "${ARTIFACTS_ROOT}" \
  "${SESSION_CACHE_ROOT}/maven" "${SESSION_CACHE_ROOT}/node" \
  "${SESSION_CACHE_ROOT}/oci" "${SESSION_CACHE_ROOT}/browser"

if [[ -e "${CACHE_POLICY_PATH}" || -L "${CACHE_POLICY_PATH}" ]]; then
  [[ -f "${CACHE_POLICY_PATH}" && ! -L "${CACHE_POLICY_PATH}" &&
      "$(stat -c %u "${CACHE_POLICY_PATH}")" == "$(id -u)" &&
      "$(stat -c %a "${CACHE_POLICY_PATH}")" =~ ^6[04]0$ ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "The cache policy marker is unsafe or foreign-owned." \
      "Reconcile the cache root without touching authoritative session state."
  jq -e \
    --arg session "${SESSION_ID}" \
    --arg runtime "${RUNTIME_ID}" '
      .schemaVersion == 1 and .sessionId == $session and
      .runtimeId == $runtime and .authoritative == false and
      .rebuildable == true and .secretsAllowed == false and
      .scopes == ["browser", "maven", "node", "oci"]
    ' "${CACHE_POLICY_PATH}" >/dev/null ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "The cache policy conflicts with the session allocation." \
      "Quarantine or rebuild only the non-authoritative cache after review."
else
  temporary_cache_policy="$(mktemp "${SESSION_CACHE_ROOT}/.cache-policy-v1.json.XXXXXX")"
  jq -n \
    --arg session "${SESSION_ID}" \
    --arg runtime "${RUNTIME_ID}" '{
      schemaVersion: 1,
      sessionId: $session,
      runtimeId: $runtime,
      authoritative: false,
      rebuildable: true,
      secretsAllowed: false,
      scopes: ["browser", "maven", "node", "oci"]
    }' >"${temporary_cache_policy}"
  chmod 0640 "${temporary_cache_policy}"
  mv -- "${temporary_cache_policy}" "${CACHE_POLICY_PATH}"
fi

if [[ ! -f "${RUNTIME_RECORD}" ]]; then
  temporary_record="$(mktemp "${SESSION_ROOT}/.runtime-allocation-v1.json.XXXXXX")"
  jq -n \
    --arg session "${SESSION_ID}" \
    --arg project "${PROJECT_ID}" \
    --arg branch "${BRANCH}" \
    --arg mirror "${MIRROR_PATH}" \
    --arg worktree "${WORKTREE_PATH}" \
    --arg runtime "${RUNTIME_ID}" \
    --arg slot "${SLOT}" \
    --arg runtimeRoot "${RUNTIME_ROOT}" \
    --arg logs "${LOGS_PATH}" \
    --arg artifacts "${ARTIFACTS_ROOT}" \
    --arg cache "${SESSION_CACHE_ROOT}" \
    --arg compose "${COMPOSE_PROJECT}" \
    --arg network "${NETWORK_NAME}" \
    --arg volume "${VOLUME_PREFIX}" \
    --arg unit "${PROCESS_UNIT}" \
    --arg tomcat "${TOMCAT_BASE}" \
    --argjson ports "${ALLOCATED_PORTS}" '{
      schemaVersion: 1,
      sessionId: $session,
      projectId: $project,
      branch: $branch,
      mirrorPath: $mirror,
      worktreePath: $worktree,
      runtimeId: $runtime,
      slot: $slot,
      workloadClass: "normal",
      state: "allocated",
      runtimeNames: {
        composeProject: $compose,
        network: $network,
        volumePrefix: $volume,
        processUnit: $unit,
        tomcatBase: $tomcat
      },
      runtimeRoot: $runtimeRoot,
      logsPath: $logs,
      artifactsRoot: $artifacts,
      cacheRoot: $cache,
      allocatedPorts: $ports
    }' >"${temporary_record}"
  chmod 0640 "${temporary_record}"
  mv -- "${temporary_record}" "${RUNTIME_RECORD}"
fi

cat "${RUNTIME_RECORD}"
