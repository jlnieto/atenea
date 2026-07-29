#!/usr/bin/env bash

set -Eeuo pipefail
umask 0007

OPERATION="${1:-}"
[[ "$#" -gt 0 ]] && shift
SESSION_ID=""
ALLOCATION_PATH=""
MANIFEST_PATH=""
LOG_TAIL="200"
JSON_MODE=false
TEST_MODE="${ATENEA_RUNTIME_MANAGER_TEST_MODE:-0}"
SERVICE_USER="atenea-worker"
PLAN_PATH=""
ATENEA_MANIFEST_SHA256="3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3"
ATENEA_COMPOSE_SHA256="2133646b9fe6227ca417d6d62c92a74306caaa46a2957cdee810d5d7b0e5bb9f"

fail() {
  local code="$1" message="$2" action="$3"
  printf '%s: %s\nNext action: %s\n' \
    "${code}" "${message}" "${action}" >&2
  exit 65
}

usage() {
  cat >&2 <<EOF
Usage:
  $0 {status|doctor|build|up|stop|restart|redeploy|logs} \
    --session <worksession-uuid> \
    --allocation <runtime-allocation-v1.json> \
    --manifest <project-runtime-v1.json> \
    [--tail <lines>] [--json]
EOF
  exit 64
}

cleanup() {
  if [[ -n "${PLAN_PATH}" && -f "${PLAN_PATH}" ]]; then
    case "${PLAN_PATH}" in
      "${CONTROL_ROOT:-/nonexistent}"/.runtime-plan-v1.*)
        rm -f -- "${PLAN_PATH}"
        ;;
    esac
  fi
}
trap cleanup EXIT

case "${OPERATION}" in
  status|doctor|build|up|stop|restart|redeploy|logs)
    ;;
  *)
    usage
    ;;
esac

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --session)
      [[ "$#" -ge 2 && -z "${SESSION_ID}" ]] || usage
      SESSION_ID="$2"
      shift 2
      ;;
    --allocation)
      [[ "$#" -ge 2 && -z "${ALLOCATION_PATH}" ]] || usage
      ALLOCATION_PATH="$2"
      shift 2
      ;;
    --manifest)
      [[ "$#" -ge 2 && -z "${MANIFEST_PATH}" ]] || usage
      MANIFEST_PATH="$2"
      shift 2
      ;;
    --tail)
      [[ "$#" -ge 2 && "${OPERATION}" == "logs" ]] || usage
      LOG_TAIL="$2"
      shift 2
      ;;
    --json)
      [[ "${JSON_MODE}" == "false" ]] || usage
      JSON_MODE=true
      shift
      ;;
    *)
      usage
      ;;
  esac
done

[[ "${SESSION_ID}" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] ||
  fail "SESSION_REQUIRED" "Session identity is not a canonical lowercase UUID." \
    "Retry with the persisted WorkSession UUID."
[[ -n "${ALLOCATION_PATH}" && -n "${MANIFEST_PATH}" ]] || usage
[[ "${LOG_TAIL}" =~ ^[0-9]+$ &&
    "${LOG_TAIL}" -ge 1 && "${LOG_TAIL}" -le 10000 ]] ||
  fail "OPERATION_FAILED" "Log tail must be between 1 and 10000 lines." \
    "Retry with a bounded --tail value."

for command in jq mktemp realpath sha256sum stat; do
  command -v "${command}" >/dev/null ||
    fail "OPERATION_FAILED" "Required command is unavailable: ${command}" \
      "Install the version-pinned worker prerequisites and retry."
done

if [[ "${TEST_MODE}" == "1" ]]; then
  SERVICE_USER="${ATENEA_WORKER_SERVICE_USER:-atenea-worker}"
  WORKSPACE_ROOT="${ATENEA_WORKSPACE_ROOT:-}"
  ARTIFACT_ROOT="${ATENEA_ARTIFACT_ROOT:-}"
  CACHE_ROOT="${ATENEA_CACHE_ROOT:-}"
  CONTROL_ROOT="${ATENEA_RUNTIME_MANAGER_CONTROL_ROOT:-}"
  ENGINE="${ATENEA_RUNTIME_ENGINE:-}"
  for path in \
    "${WORKSPACE_ROOT}" "${ARTIFACT_ROOT}" "${CACHE_ROOT}" \
    "${CONTROL_ROOT}" "${ENGINE}"; do
    [[ "${path}" == /tmp/* && "${path}" != *".."* ]] ||
      fail "OPERATION_FAILED" "Test manager paths must be explicit beneath /tmp." \
        "Use a fresh synthetic test directory."
  done
  EXPECTED_OWNER="$(id -u)"
else
  [[ "${EUID}" -eq 0 ]] ||
    fail "OPERATION_FAILED" "The runtime manager requires its root-owned boundary." \
      "Invoke it through the installed mediated runtime client."
  [[ "${SUDO_USER:-}" == "${SERVICE_USER}" &&
      "${SUDO_UID:-}" == "$(id -u "${SERVICE_USER}")" ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "The runtime caller is not the worker service identity." \
      "Invoke dev as the persisted worker service identity."
  WORKSPACE_ROOT="/srv/atenea/workspaces"
  ARTIFACT_ROOT="/srv/atenea/artifacts"
  CACHE_ROOT="/srv/atenea/caches"
  CONTROL_ROOT="/srv/atenea/worker/runtime-manager-v1"
  ENGINE="/usr/libexec/atenea-runtime-engine-v1"
  EXPECTED_OWNER="$(id -u "${SERVICE_USER}")"
fi

for root in "${WORKSPACE_ROOT}" "${ARTIFACT_ROOT}" "${CACHE_ROOT}"; do
  [[ -d "${root}" && ! -L "${root}" ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "A managed runtime root is missing or unsafe." \
    "Restore the worker-owned filesystem skeleton."
done
[[ -d "${CONTROL_ROOT}" && ! -L "${CONTROL_ROOT}" &&
    "$(stat -c %u "${CONTROL_ROOT}")" == "$(id -u)" ]] ||
  fail "RUNTIME_OWNERSHIP_CONFLICT" "Runtime manager control root is missing or unsafe." \
    "Restore the root-owned manager control directory."
[[ -f "${ENGINE}" && ! -L "${ENGINE}" && -x "${ENGINE}" ]] ||
  fail "TOOLCHAIN_UNAVAILABLE" "The fixed runtime engine is unavailable." \
    "Install the reviewed runtime engine before accepting lifecycle work."
if [[ "${TEST_MODE}" == "1" ]]; then
  [[ "$(stat -c %u "${ENGINE}")" == "$(id -u)" ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "Synthetic runtime engine is foreign-owned." \
      "Use an owned engine fixture beneath the test root."
else
  [[ "$(stat -c %u "${ENGINE}")" == "0" &&
      "$(stat -c %a "${ENGINE}")" =~ ^[57][0-5][0-5]$ ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "Runtime engine ownership or mode is unsafe." \
      "Restore the reviewed root-owned runtime engine."
fi

SESSION_ROOT="${WORKSPACE_ROOT}/sessions/${SESSION_ID}"
EXPECTED_ALLOCATION="${SESSION_ROOT}/runtime-allocation-v1.json"
WORKSPACE_RECORD="${SESSION_ROOT}/workspace-v1.json"
[[ "${ALLOCATION_PATH}" == "${EXPECTED_ALLOCATION}" ]] ||
  fail "SESSION_IDENTITY_CONFLICT" "Allocation path does not belong to the selected WorkSession." \
    "Use the allocation persisted for this WorkSession."

assert_owned_record() {
  local path="$1" description="$2"
  [[ -f "${path}" && ! -L "${path}" &&
      "$(stat -c %u "${path}")" == "${EXPECTED_OWNER}" &&
      "$(stat -c %a "${path}")" =~ ^6[04]0$ ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "${description} is missing, unsafe or foreign-owned." \
      "Reconcile the worker-owned record without changing project source."
}

assert_owned_record "${ALLOCATION_PATH}" "Runtime allocation record"
assert_owned_record "${WORKSPACE_RECORD}" "Workspace ownership record"

jq -e \
  --arg session "${SESSION_ID}" \
  --arg workspaceRoot "${WORKSPACE_ROOT}" \
  --arg artifactRoot "${ARTIFACT_ROOT}" \
  --arg cacheRoot "${CACHE_ROOT}" '
    (
      (
        .projectId == "atenea" and
        (keys | sort) == [
          "allocatedPorts", "artifactsRoot", "branch", "cacheRoot",
          "heavyPermit", "logsPath", "manifestRelativePath", "mirrorPath",
          "projectId", "runtimeId", "runtimeNames", "runtimeRoot",
          "schemaVersion", "sessionId", "slot", "state", "workloadClass",
          "worktreePath"
        ] and
        .workloadClass == "heavy" and
        (.heavyPermit | test("^heavy[1-2]$"))
      ) or (
        .projectId != "atenea" and
        (keys | sort) == [
          "allocatedPorts", "artifactsRoot", "branch", "cacheRoot", "logsPath",
          "manifestRelativePath", "mirrorPath", "projectId", "runtimeId",
          "runtimeNames", "runtimeRoot", "schemaVersion", "sessionId", "slot",
          "state", "workloadClass", "worktreePath"
        ] and
        .workloadClass == "normal"
      )
    ) and
    .schemaVersion == 1 and .state == "allocated" and
    .sessionId == $session and
    (.projectId | test("^[a-z][a-z0-9-]{1,62}$")) and
    .runtimeId == ("ws-" + ($session | gsub("-"; ""))) and
    (.slot | test("^slot[1-4]$")) and
    .worktreePath == (
      $workspaceRoot + "/sessions/" + $session + "/" + .projectId
    ) and
    .runtimeRoot == (
      $workspaceRoot + "/sessions/" + $session +
      "/runtime/" + .runtimeId
    ) and
    .runtimeNames.composeProject == (.runtimeId + "-compose") and
    .runtimeNames.network == (.runtimeId + "-network") and
    .runtimeNames.volumePrefix == (.runtimeId + "-volume") and
    .runtimeNames.processUnit == ("atenea-" + .runtimeId + ".service") and
    .runtimeNames.tomcatBase == (.runtimeRoot + "/tomcat") and
    .logsPath == (
      $artifactRoot + "/sessions/" + $session + "/runtime/logs"
    ) and
    .artifactsRoot == (
      $artifactRoot + "/sessions/" + $session + "/runs"
    ) and
    .cacheRoot == (
      $cacheRoot + "/sessions/" + $session
    ) and
    (.manifestRelativePath |
      test("^(?!/)(?!~)(?!.*(?:^|/)\\.\\.(?:/|$))(?!.*//)[A-Za-z0-9._/-]+$")) and
    (.allocatedPorts | type == "array" and length > 0) and
    all(.allocatedPorts[];
      (.name | test("^[a-z][a-z0-9-]{1,62}$")) and
      (.internalPort | type == "number" and floor == . and
        . >= 1 and . <= 65535) and
      (.protocol == "http" or .protocol == "tcp") and
      .bindAddress == "127.0.0.1" and
      (.loopbackPort | type == "number" and floor == . and
        . >= 1024 and . <= 65535))
  ' "${ALLOCATION_PATH}" >/dev/null ||
  fail "RECONCILIATION_REQUIRED" "Runtime allocation is invalid or incompatible." \
    "Reconcile the allocation before using the runtime manager."

PROJECT_ID="$(jq -r '.projectId' "${ALLOCATION_PATH}")"
WORKTREE_PATH="$(jq -r '.worktreePath' "${ALLOCATION_PATH}")"
RUNTIME_ID="$(jq -r '.runtimeId' "${ALLOCATION_PATH}")"
MANIFEST_RELATIVE="$(jq -r '.manifestRelativePath' "${ALLOCATION_PATH}")"
IS_ATENEA=false
[[ "${PROJECT_ID}" == "atenea" ]] && IS_ATENEA=true

[[ -d "${SESSION_ROOT}" && ! -L "${SESSION_ROOT}" &&
    "$(stat -c %u "${SESSION_ROOT}")" == "${EXPECTED_OWNER}" &&
    -d "${WORKTREE_PATH}" && ! -L "${WORKTREE_PATH}" &&
    "$(stat -c %u "${WORKTREE_PATH}")" == "${EXPECTED_OWNER}" ]] ||
  fail "RUNTIME_OWNERSHIP_CONFLICT" "Session root or worktree ownership is unsafe." \
    "Reconcile the WorkSession without resetting or cleaning source."

assert_owned_directory() {
  local path="$1" description="$2"
  [[ -d "${path}" && ! -L "${path}" &&
      "$(stat -c %u "${path}")" == "${EXPECTED_OWNER}" ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "${description} is missing, unsafe or foreign-owned." \
      "Reconcile the exact WorkSession-owned path before retrying."
}

if [[ "${IS_ATENEA}" == "true" ]]; then
  if [[ "${TEST_MODE}" == "1" ]]; then
    ADMISSION_RECORD="${ATENEA_RUNTIME_ADMISSION_RECORD:-}"
    [[ "${ADMISSION_RECORD}" == /tmp/* && "${ADMISSION_RECORD}" != *".."* ]] ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "Synthetic Atenea admission record is outside /tmp." \
        "Use the persisted synthetic admission record for this WorkSession."
  else
    ADMISSION_RECORD="/srv/atenea/worker/runtime-admission-v1/records/${SESSION_ID}.json"
  fi
  assert_owned_record "${ADMISSION_RECORD}" "Atenea admission record"
  jq -e \
    --arg session "${SESSION_ID}" \
    --arg slot "$(jq -r '.slot' "${ALLOCATION_PATH}")" \
    --arg permit "$(jq -r '.heavyPermit' "${ALLOCATION_PATH}")" '
      (keys | sort) == ["heavy", "normal", "schemaVersion", "sessionId"] and
      .schemaVersion == 1 and .sessionId == $session and
      .normal == {slot: $slot, state: "held"} and
      .heavy == {permit: $permit, state: "held"}
    ' "${ADMISSION_RECORD}" >/dev/null ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "Atenea slot or heavy permit is not held by this WorkSession." \
      "Reconcile the persisted admission and allocation records before retrying."

  for path in \
    "$(jq -r '.runtimeRoot' "${ALLOCATION_PATH}")" \
    "$(jq -r '.logsPath' "${ALLOCATION_PATH}")" \
    "$(jq -r '.artifactsRoot' "${ALLOCATION_PATH}")" \
    "$(jq -r '.cacheRoot' "${ALLOCATION_PATH}")" \
    "$(jq -r '.cacheRoot' "${ALLOCATION_PATH}")/codex" \
    "$(jq -r '.cacheRoot' "${ALLOCATION_PATH}")/maven" \
    "$(jq -r '.cacheRoot' "${ALLOCATION_PATH}")/node" \
    "$(jq -r '.runtimeRoot' "${ALLOCATION_PATH}")/data/uploads" \
    "$(jq -r '.runtimeRoot' "${ALLOCATION_PATH}")/secrets"; do
    assert_owned_directory "${path}" "Atenea runtime path"
  done
  for secret_name in ATENEA_DEV_POSTGRES_PASSWORD ATENEA_DEV_JWT_SECRET; do
    secret_path="$(jq -r '.runtimeRoot' "${ALLOCATION_PATH}")/secrets/${secret_name}"
    [[ -f "${secret_path}" && ! -L "${secret_path}" &&
        "$(stat -c %u "${secret_path}")" == "${EXPECTED_OWNER}" &&
        "$(stat -c %a "${secret_path}")" == "600" ]] ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "A named Atenea secret reference is missing or unsafe." \
        "Resolve the named development-only secret into the owned session secret path."
  done
fi

jq -e \
  --arg session "${SESSION_ID}" \
  --arg project "${PROJECT_ID}" \
  --arg worktree "${WORKTREE_PATH}" \
  --arg branch "$(jq -r '.branch' "${ALLOCATION_PATH}")" \
  --arg mirror "$(jq -r '.mirrorPath' "${ALLOCATION_PATH}")" '
    .schemaVersion == 1 and .state == "ready" and
    .sessionId == $session and .projectId == $project and
    .worktreePath == $worktree and .branch == $branch and
    .mirrorPath == $mirror
  ' "${WORKSPACE_RECORD}" >/dev/null ||
  fail "SESSION_IDENTITY_CONFLICT" "Workspace and runtime identities do not match." \
    "Reconcile the persisted WorkSession records."

EXPECTED_MANIFEST="${WORKTREE_PATH}/${MANIFEST_RELATIVE}"
[[ "${MANIFEST_PATH}" == "${EXPECTED_MANIFEST}" &&
    -f "${MANIFEST_PATH}" && ! -L "${MANIFEST_PATH}" ]] ||
  fail "MANIFEST_INVALID" "Manifest path is not the reviewed WorkSession manifest." \
    "Restore the persisted repository-relative manifest."
WORKTREE_REAL="$(realpath -e "${WORKTREE_PATH}")"
MANIFEST_REAL="$(realpath -e "${MANIFEST_PATH}")"
[[ "${MANIFEST_REAL}" == "${WORKTREE_REAL}/"* ]] ||
  fail "MANIFEST_INVALID" "Manifest escapes the owned WorkSession worktree." \
    "Restore the reviewed repository-relative manifest."
if [[ "${IS_ATENEA}" == "true" ]]; then
  [[ "${MANIFEST_RELATIVE}" == "ops/atenea-runtime.json" &&
      "$(sha256sum "${MANIFEST_REAL}" | cut -d' ' -f1)" == "${ATENEA_MANIFEST_SHA256}" ]] ||
    fail "MANIFEST_INVALID" "Atenea manifest path or SHA-256 differs from the reviewed contract." \
      "Restore the exact committed Atenea runtime manifest."
  ATENEA_COMPOSE_PATH="${WORKTREE_REAL}/ops/worker/docker-compose.ax42.yml"
  [[ -f "${ATENEA_COMPOSE_PATH}" && ! -L "${ATENEA_COMPOSE_PATH}" &&
      "$(sha256sum "${ATENEA_COMPOSE_PATH}" | cut -d' ' -f1)" == "${ATENEA_COMPOSE_SHA256}" ]] ||
    fail "MANIFEST_INVALID" "Atenea Compose path or SHA-256 differs from the reviewed contract." \
      "Restore the exact committed AX42 Compose definition."
fi

jq -e \
  --arg project "${PROJECT_ID}" \
  --argjson allocatedPorts "$(
    jq -cS '[.allocatedPorts[] | {
      name: .name,
      port: .internalPort,
      protocol: .protocol
    }] | sort_by(.name)' "${ALLOCATION_PATH}"
  )" '
    def identifier:
      type == "string" and test("^[a-z][a-z0-9-]{1,62}$");
    def relativePath:
      type == "string" and length > 0 and
      test("^(?!/)(?!~)(?!.*(?:^|/)\\.\\.(?:/|$))(?!.*//)[A-Za-z0-9._/-]+$");
    def routePath:
      type == "string" and test("^/(?!/)[A-Za-z0-9._~!$&\u0027()*+,;=:@%/-]*$");
    def runtimeCommand:
      type == "object" and
      ((keys - ["argv", "cwd", "secretRefs", "timeoutSeconds"]) | length) == 0 and
      (.argv | type == "array" and length > 0) and
      all(.argv[];
        type == "string" and length > 0 and length <= 512 and
        (test("(PASSWORD|TOKEN|SECRET|API_KEY)=") | not)) and
      (.timeoutSeconds | type == "number" and floor == . and
        . >= 1 and . <= 3600) and
      ((has("cwd") | not) or (.cwd | relativePath)) and
      ((has("secretRefs") | not) or
        (.secretRefs | type == "array" and length == (unique | length) and
          all(.[]; type == "string" and test("^[A-Z][A-Z0-9_]{2,63}$"))));
    def internalPort:
      type == "object" and
      (keys | sort) == ["name", "port", "protocol"] and
      (.name | identifier) and
      (.port | type == "number" and floor == . and . >= 1 and . <= 65535) and
      (.protocol == "http" or .protocol == "tcp");
    (keys | sort) == [
      "artifacts", "browserChecks", "lifecycle", "preview", "project",
      "runtime", "schemaVersion", "secrets", "toolchains", "workloadClass"
    ] and
    .schemaVersion == 1 and
    (.project | type == "object" and (keys | sort) == ["id", "repository"]) and
    (.project.id == $project and (.project.id | identifier)) and
    (.project.repository |
      type == "object" and (keys | sort) == ["defaultBranch", "url"] and
      (.url | type == "string" and
        test("^https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\\.git$")) and
      (.defaultBranch | type == "string" and
        test("^(?!-)(?!.*\\.\\.)(?!.*[~^:?*\\[\\\\])[A-Za-z0-9._/-]+$"))) and
    (.toolchains | type == "array" and length > 0) and
    all(.toolchains[];
      type == "object" and (keys | sort) == ["name", "source", "version"] and
      (.name == "git" or .name == "java-build" or .name == "java-runtime" or
       .name == "maven" or .name == "node" or .name == "docker" or
       .name == "compose" or .name == "chromium" or .name == "playwright") and
      (.version | type == "string" and test("^[0-9][A-Za-z0-9.+_-]{0,63}$")) and
      (.source == "worker-package" or .source == "container-image" or
       .source == "project-wrapper")) and
    (
      (.workloadClass == "normal" and $project != "atenea") or
      (.workloadClass == "heavy" and $project == "atenea")
    ) and
    (.lifecycle | keys | sort) ==
      ["build", "health", "logs", "start", "stop"] and
    all(.lifecycle[]; runtimeCommand) and
    ([.runtime.internalPorts[] | {
      name: .name,
      port: .port,
      protocol: .protocol
    }] | sort_by(.name)) == $allocatedPorts and
    all(.runtime.internalPorts[]; internalPort) and
    ([.runtime.internalPorts[].name] | length == (unique | length)) and
    (
      (.runtime.kind == "compose" and
       (.runtime | keys | sort) ==
         ["composeFiles", "internalPorts", "kind", "services"] and
       (.runtime.composeFiles | type == "array" and length > 0) and
       all(.runtime.composeFiles[];
         relativePath) and
       (.runtime.services | type == "array" and length > 0 and
         length == (unique | length) and all(.[]; identifier)))
      or
      (.runtime.kind == "tomcat" and
       (.runtime | keys | sort) == [
         "contextPath", "internalPorts", "kind", "warPath", "webappModule"
       ] and
       (.runtime.webappModule | relativePath) and
       (.runtime.warPath | relativePath) and
       (.runtime.contextPath | routePath))
    ) and
    (.preview |
      type == "object" and
      ((keys - [
        "command", "internalPort", "localhostCompatibilityRequired", "path",
        "publish"
      ]) | length) == 0 and
      (.internalPort | identifier) and
      (.path | routePath) and .publish == "private" and
      (.command | runtimeCommand) and
      ((has("localhostCompatibilityRequired") | not) or
        (.localhostCompatibilityRequired | type == "boolean"))) and
    .preview.internalPort as $previewPort |
      any(.runtime.internalPorts[]; .name == $previewPort) and
    (.browserChecks | type == "array" and length > 0) and
    all(.browserChecks[];
      type == "object" and
      (keys | sort) == [
        "artifact", "command", "name", "path", "viewports"
      ] and
      (.name | identifier) and (.path | routePath) and
      (.command | runtimeCommand) and
      (.viewports | type == "array" and length > 0 and
        length == (unique | length) and
        all(.[];
          . == "desktop-1440x900" or . == "mobile-390x844" or
          . == "tablet-768x1024")) and
      (.artifact | relativePath)) and
    (.artifacts | type == "array" and length > 0) and
    all(.artifacts[];
      type == "object" and
      (keys | sort) == ["name", "path", "retention"] and
      (.name | identifier) and (.path | relativePath) and
      (.retention == "run" or .retention == "session")) and
    (.secrets | type == "array") and
    all(.secrets[];
      type == "object" and
      ((keys - ["exposure", "name", "purpose", "required"]) | length) == 0 and
      (.name | type == "string" and test("^[A-Z][A-Z0-9_]{2,63}$")) and
      (.purpose | type == "string" and length >= 3 and length <= 160) and
      (.exposure == "build" or .exposure == "runtime" or
       .exposure == "browser") and
      ((has("required") | not) or (.required | type == "boolean")))
  ' "${MANIFEST_REAL}" >/dev/null ||
  fail "MANIFEST_INVALID" "Manifest violates the reviewed runtime-manager contract." \
    "Validate the manifest schema and remove unsupported authority."

INSPECTION="$(
  "${ENGINE}" inspect \
    --session "${SESSION_ID}" \
    --allocation "${ALLOCATION_PATH}" \
    --manifest "${MANIFEST_REAL}" 2>/dev/null
)" ||
  fail "OPERATION_FAILED" "Runtime policy inspection failed." \
    "Inspect the session-scoped engine diagnostics and retry."

if [[ "$(jq -r '.runtime.kind' "${MANIFEST_REAL}")" == "compose" ]]; then
  EXPECTED_SERVICES="$(jq -cS '.runtime.services | sort' "${MANIFEST_REAL}")"
else
  EXPECTED_SERVICES='["tomcat"]'
fi
if [[ "${IS_ATENEA}" == "true" ]]; then
  jq -e -s \
    --arg session "${SESSION_ID}" \
    --arg runtime "${RUNTIME_ID}" \
    --arg slot "$(jq -r '.slot' "${ALLOCATION_PATH}")" \
    --arg worktree "${WORKTREE_PATH}" \
    --arg runtimeRoot "$(jq -r '.runtimeRoot' "${ALLOCATION_PATH}")" \
    --arg cache "$(jq -r '.cacheRoot' "${ALLOCATION_PATH}")" \
    --arg logs "$(jq -r '.logsPath' "${ALLOCATION_PATH}")" \
    --arg compose "$(jq -r '.runtimeNames.composeProject' "${ALLOCATION_PATH}")" \
    --arg network "$(jq -r '.runtimeNames.network' "${ALLOCATION_PATH}")" \
    --arg volume "$(jq -r '.runtimeNames.volumePrefix' "${ALLOCATION_PATH}")" \
    --arg allocationSha "$(sha256sum "${ALLOCATION_PATH}" | cut -d' ' -f1)" \
    --arg manifestSha "${ATENEA_MANIFEST_SHA256}" \
    --arg composePath "${ATENEA_COMPOSE_PATH}" \
    --arg composeSha "${ATENEA_COMPOSE_SHA256}" \
    --arg deliveryBase "${ATENEA_RUNTIME_DELIVERY_BASE:-/tmp/atenea-runtime-delivery}" '
      def labels($service): {
        "com.atenea.engine": "atenea-runtime-engine-v1",
        "com.atenea.session": $session,
        "com.atenea.runtime": $runtime,
        "com.atenea.project": "atenea",
        "com.atenea.service": $service
      };
      length == 1 and
      (.[0] | keys | sort) == [
        "allocationSha256", "compose", "manifestSha256", "projectId",
        "runtimeId", "schemaVersion", "services", "sessionId", "slot"
      ] and
      .[0].schemaVersion == 1 and .[0].sessionId == $session and
      .[0].runtimeId == $runtime and .[0].projectId == "atenea" and
      .[0].slot == $slot and .[0].allocationSha256 == $allocationSha and
      .[0].manifestSha256 == $manifestSha and
      ($deliveryBase + "/" + $runtime) as $delivery |
      .[0].compose == {
        sourcePath: $composePath,
        sourceSha256: $composeSha,
        projectName: $compose,
        delivery: {
          root: $delivery,
          source: ($delivery + "/source"),
          archiveSha256: "ef785418b977fcab10b3cc2451c2ed6a2f15c7a1ec659a3ed14f03ec1a5b1a76",
          commit: "b605c8d5b063e7321edd60fec2265ec7ddb84ea9",
          tree: "7a661346cbe0cab61485e52593d4ddfc8a4068a8",
          logs: $logs
        },
        network: {
          name: $network,
          internal: true,
          labels: labels("runtime")
        },
        volumes: [{
          name: ($volume + "-db-data"),
          labels: labels("db")
        }]
      } and
      ([.[0].services[].name] | sort) ==
        ["atenea-dev", "codex-app-server", "db"] and
      all(.[0].services[];
        (keys | sort) == [
          "capabilities", "containerName", "daemonSockets", "devices",
          "image", "labels", "mounts", "name", "namespaces", "ports",
          "resourceNames", "secretRefs", "unsupportedFields"
        ] and
        .containerName == ($runtime + "-" + .name) and
        .labels == labels(.name) and
        (.ports | type == "array" and length == 1) and
        all(.ports[];
          .bindAddress == "127.0.0.1" and
          (.loopbackPort | type == "number" and floor == . and
            . >= 1024 and . <= 65535)) and
        .namespaces == [] and .capabilities == [] and .devices == [] and
        .daemonSockets == [] and .unsupportedFields == [] and
        all(.resourceNames[];
          . == $compose or . == $network or
          . == ($volume + "-db-data") or startswith($runtime + "-"))) and
      (.[0].services[] | select(.name == "db")) as $db |
      $db.image ==
        "postgres:16@sha256:33f923b05f64ca54ac4401c01126a6b92afe839a0aa0a52bc5aeb5cc958e5f20" and
      $db.mounts == [{
        type: "volume",
        source: ($volume + "-db-data"),
        target: "/var/lib/postgresql/data",
        readOnly: false
      }] and
      $db.ports == [(
        $db.ports[0] |
        select(.name == "postgres" and .internalPort == 5432 and .protocol == "tcp")
      )] and
      $db.secretRefs == ["ATENEA_DEV_POSTGRES_PASSWORD"] and
      (.[0].services[] | select(.name == "codex-app-server")) as $codex |
      $codex.image ==
        "sha256:c081aaa9d40afa4d8b57297000fe9aff5635e52a94b2b87abf8626b128c55e2d" and
      $codex.mounts == [
        {type: "bind", source: ($delivery + "/source"), target: "/workspace/atenea", readOnly: false},
        {type: "bind", source: ($delivery + "/cache/codex"), target: "/workspace/cache/codex", readOnly: false}
      ] and
      $codex.ports == [(
        $codex.ports[0] |
        select(.name == "codex" and .internalPort == 8092 and .protocol == "tcp")
      )] and $codex.secretRefs == [] and
      (.[0].services[] | select(.name == "atenea-dev")) as $app |
      $app.image ==
        "maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e" and
      $app.mounts == [
        {type: "bind", source: ($delivery + "/source"), target: "/workspace/atenea", readOnly: false},
        {type: "bind", source: ($delivery + "/cache/maven"), target: "/workspace/cache/maven", readOnly: false},
        {type: "bind", source: ($delivery + "/cache/node"), target: "/workspace/cache/node", readOnly: false},
        {type: "bind", source: ($delivery + "/data/uploads"), target: "/workspace/data/uploads", readOnly: false}
      ] and
      $app.ports == [(
        $app.ports[0] |
        select(.name == "web" and .internalPort == 8081 and .protocol == "http")
      )] and
      $app.secretRefs == [
        "ATENEA_DEV_POSTGRES_PASSWORD",
        "ATENEA_DEV_JWT_SECRET"
      ]
    ' <<<"${INSPECTION}" >/dev/null ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "Resolved Atenea runtime differs from the exact allowlisted adapter." \
      "Restore the reviewed services, images, mounts, ports, labels and resource identities."
else
  jq -e -s \
    --arg session "${SESSION_ID}" \
    --arg runtime "${RUNTIME_ID}" \
    --arg compose "$(jq -r '.runtimeNames.composeProject' "${ALLOCATION_PATH}")" \
    --arg network "$(jq -r '.runtimeNames.network' "${ALLOCATION_PATH}")" \
    --arg volume "$(jq -r '.runtimeNames.volumePrefix' "${ALLOCATION_PATH}")" \
    --arg unit "$(jq -r '.runtimeNames.processUnit' "${ALLOCATION_PATH}")" \
    --arg tomcat "$(jq -r '.runtimeNames.tomcatBase' "${ALLOCATION_PATH}")" \
    --argjson expectedServices "${EXPECTED_SERVICES}" '
    length == 1 and
    .[0].schemaVersion == 1 and
    .[0].sessionId == $session and .[0].runtimeId == $runtime and
    (.[0] | keys | sort) ==
      ["runtimeId", "schemaVersion", "services", "sessionId"] and
    (.[0].services | type == "array" and length > 0) and
    ([.[0].services[].name] | sort) == $expectedServices and
    all(.[0].services[];
      (keys | sort) == [
        "capabilities", "daemonSockets", "devices", "mounts", "name",
        "namespaces", "resourceNames", "unsupportedFields"
      ] and
      (.name | test("^[a-z][a-z0-9-]{1,62}$")) and
      (.mounts | type == "array" and length == 0) and
      (.namespaces | type == "array" and length == 0) and
      (.capabilities | type == "array" and length == 0) and
      (.devices | type == "array" and length == 0) and
      (.daemonSockets | type == "array" and length == 0) and
      (.unsupportedFields | type == "array" and length == 0) and
      (.resourceNames | type == "array") and
      all(.resourceNames[];
        . == $compose or . == $network or . == $unit or . == $tomcat or
        startswith($volume + "-")))
    ' <<<"${INSPECTION}" >/dev/null ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "Resolved runtime requests forbidden authority or foreign resources." \
      "Remove mounts, host namespaces, capabilities, devices, daemon sockets or foreign names."
fi

PLAN_PATH="$(mktemp "${CONTROL_ROOT}/.runtime-plan-v1.XXXXXX")"
jq -n \
  --arg operation "${OPERATION}" \
  --arg session "${SESSION_ID}" \
  --arg project "${PROJECT_ID}" \
  --arg runtime "${RUNTIME_ID}" \
  --arg allocation "${ALLOCATION_PATH}" \
  --arg manifest "${MANIFEST_REAL}" \
  --arg slot "$(jq -r '.slot' "${ALLOCATION_PATH}")" \
  --argjson tail "${LOG_TAIL}" \
  --argjson runtimeNames "$(jq -c '.runtimeNames' "${ALLOCATION_PATH}")" \
  --argjson allocatedPorts "$(jq -c '.allocatedPorts' "${ALLOCATION_PATH}")" \
  --argjson ateneaAdapter "$(
    if [[ "${IS_ATENEA}" == "true" ]]; then
      jq -c '.' <<<"${INSPECTION}"
    else
      printf 'null'
    fi
  )" \
  --argjson mountsAllowed "$(
    if [[ "${IS_ATENEA}" == "true" ]]; then
      jq -c '[.services[].mounts[] | select(.type == "bind") | .source] |
        unique | sort' <<<"${INSPECTION}"
    else
      printf '[]'
    fi
  )" '{
    schemaVersion: 1,
    operation: $operation,
    sessionId: $session,
    projectId: $project,
    runtimeId: $runtime,
    allocationPath: $allocation,
    manifestPath: $manifest,
    logTail: $tail,
    runtimeNames: $runtimeNames,
    allocatedPorts: $allocatedPorts,
    restrictions: {
      noNewPrivileges: true,
      readOnlyRootFilesystem: true,
      dropAllCapabilities: true,
      hostNetwork: false,
      hostPid: false,
      hostIpc: false,
      devicesAllowed: false,
      daemonSocketsAllowed: false,
      mountsAllowed: $mountsAllowed
    }
  } |
  if $project == "atenea" then
    .slot = $slot |
    .ateneaAdapter = $ateneaAdapter |
    .restrictions.secretRefsAllowed = [
      "ATENEA_DEV_JWT_SECRET",
      "ATENEA_DEV_POSTGRES_PASSWORD"
    ]
  else
    .
  end' >"${PLAN_PATH}"
chmod 0600 "${PLAN_PATH}"

if [[ "${JSON_MODE}" == "true" ]]; then
  RESULT="$("${ENGINE}" execute --plan "${PLAN_PATH}" --json 2>/dev/null)" ||
    fail "OPERATION_FAILED" "The mediated runtime operation failed." \
      "Inspect the session-scoped engine diagnostics and retry."
  jq -e -s '
    length == 1 and
    (.[0] | keys | sort) == ["healthState", "state"] and
    (.[0].state |
      . == "pending" or . == "running" or . == "ready" or
      . == "stopped" or . == "reconciling") and
    (.[0].healthState |
      . == "unknown" or . == "starting" or . == "healthy" or
      . == "unhealthy" or . == "stopped")
  ' <<<"${RESULT}" >/dev/null ||
    fail "OPERATION_FAILED" "Runtime engine returned an incompatible result." \
      "Reconcile the versioned manager and engine contracts."
  jq -c -s '.[0]' <<<"${RESULT}"
else
  RESULT="$("${ENGINE}" execute --plan "${PLAN_PATH}" 2>/dev/null)" ||
    fail "OPERATION_FAILED" "The mediated runtime operation failed." \
      "Inspect the session-scoped engine diagnostics and retry."
  printf '%s\n' "${RESULT}"
fi
