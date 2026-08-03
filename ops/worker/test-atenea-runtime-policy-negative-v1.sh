#!/usr/bin/env bash

set -Eeuo pipefail
umask 0077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MANAGER_SOURCE="${SCRIPT_DIR}/runtime-manager-v1.sh"
ENGINE_SOURCE="${SCRIPT_DIR}/runtime-engine-v1.sh"
SOURCE_ROOT="${ATENEA_RELOCATION_SOURCE_ROOT:-}"
TEST_ROOT="$(mktemp -d /tmp/atenea-runtime-policy-negative-test.XXXXXX)"

cleanup() {
  case "${TEST_ROOT}" in
    /tmp/atenea-runtime-policy-negative-test.*)
      chmod -R u+w "${TEST_ROOT}" 2>/dev/null || true
      rm -rf -- "${TEST_ROOT}"
      ;;
  esac
}
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

expect_failure() {
  local expected_code="$1"
  shift
  local output
  if output="$("$@" 2>&1)"; then
    fail "command unexpectedly succeeded: $*"
  fi
  grep -q "^${expected_code}:" <<<"${output}" ||
    fail "expected ${expected_code}, got: ${output}"
}

[[ "${SOURCE_ROOT}" == /tmp/* && "${SOURCE_ROOT}" != *".."* ]] ||
  fail "ATENEA_RELOCATION_SOURCE_ROOT must be a synthetic copy beneath /tmp"
SOURCE_MANIFEST="${SOURCE_ROOT}/ops/atenea-runtime.json"
SOURCE_COMPOSE="${SOURCE_ROOT}/ops/worker/docker-compose.ax42.yml"
[[ -f "${SOURCE_MANIFEST}" && -f "${SOURCE_COMPOSE}" ]] ||
  fail "the exact Atenea manifest and AX42 Compose inputs are required"
[[ "$(sha256sum "${SOURCE_MANIFEST}" | cut -d' ' -f1)" == \
    "327a0c521017109d7c0067a11e7d8c3ad2079de4ea78d28296848f9de39c164b" ]] ||
  fail "Atenea manifest input hash differs"
[[ "$(sha256sum "${SOURCE_COMPOSE}" | cut -d' ' -f1)" == \
    "2133646b9fe6227ca417d6d62c92a74306caaa46a2957cdee810d5d7b0e5bb9f" ]] ||
  fail "Atenea Compose input hash differs"
grep -Fqx \
  '      ATENEA_MOBILE_UPLOAD_ROOT: /workspace/data/uploads' \
  "${SOURCE_COMPOSE}" ||
  fail "Atenea Compose does not bind mobile uploads to the owned runtime path"

SESSION="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8daa"
RUNTIME="ws-${SESSION//-/}"
WORKSPACE_ROOT="${TEST_ROOT}/workspaces"
ARTIFACT_ROOT="${TEST_ROOT}/artifacts"
CACHE_ROOT="${TEST_ROOT}/caches"
CONTROL_ROOT="${TEST_ROOT}/manager-control"
SESSION_ROOT="${WORKSPACE_ROOT}/sessions/${SESSION}"
WORKTREE="${SESSION_ROOT}/atenea"
RUNTIME_ROOT="${SESSION_ROOT}/runtime/${RUNTIME}"
LOGS_PATH="${ARTIFACT_ROOT}/sessions/${SESSION}/runtime/logs"
ARTIFACTS_ROOT="${ARTIFACT_ROOT}/sessions/${SESSION}/runs"
SESSION_CACHE="${CACHE_ROOT}/sessions/${SESSION}"
MANIFEST="${WORKTREE}/ops/atenea-runtime.json"
COMPOSE="${WORKTREE}/ops/worker/docker-compose.ax42.yml"
ALLOCATION="${SESSION_ROOT}/runtime-allocation-v1.json"
ADMISSION="${TEST_ROOT}/admission/${SESSION}.json"
MANAGER="${TEST_ROOT}/runtime-manager-v1"
ENGINE="${TEST_ROOT}/runtime-engine-v1"
ENGINE_WRAPPER="${TEST_ROOT}/runtime-engine-wrapper-v1"
DOCKER="${TEST_ROOT}/docker"
DOCKER_LOG="${TEST_ROOT}/docker.log"
ENGINE_LOG="${TEST_ROOT}/engine.log"
BASE_PLAN="${TEST_ROOT}/runtime-plan-v1.json"
MUTATED_PLAN="${TEST_ROOT}/mutated-plan-v1.json"

mkdir -p \
  "${WORKTREE}/ops/worker" "${RUNTIME_ROOT}/data/uploads" \
  "${RUNTIME_ROOT}/secrets" "${LOGS_PATH}" "${ARTIFACTS_ROOT}" \
  "${SESSION_CACHE}/codex" "${SESSION_CACHE}/maven" "${SESSION_CACHE}/node" \
  "${CONTROL_ROOT}" "$(dirname -- "${ADMISSION}")"
install -m 0640 "${SOURCE_MANIFEST}" "${MANIFEST}"
install -m 0640 "${SOURCE_COMPOSE}" "${COMPOSE}"
install -m 0600 /dev/null \
  "${RUNTIME_ROOT}/secrets/ATENEA_DEV_POSTGRES_PASSWORD"
install -m 0600 /dev/null \
  "${RUNTIME_ROOT}/secrets/ATENEA_DEV_JWT_SECRET"
install -m 0750 "${MANAGER_SOURCE}" "${MANAGER}"
install -m 0750 "${ENGINE_SOURCE}" "${ENGINE}"

jq -n \
  --arg session "${SESSION}" \
  --arg worktree "${WORKTREE}" \
  --arg mirror "${TEST_ROOT}/repositories/atenea.git" '{
    schemaVersion: 1,
    sessionId: $session,
    projectId: "atenea",
    branch: ("atenea/session-" + $session),
    mirrorPath: $mirror,
    worktreePath: $worktree,
    state: "ready"
  }' >"${SESSION_ROOT}/workspace-v1.json"
chmod 0640 "${SESSION_ROOT}/workspace-v1.json"

jq -n \
  --arg session "${SESSION}" \
  --arg runtime "${RUNTIME}" \
  --arg worktree "${WORKTREE}" \
  --arg mirror "${TEST_ROOT}/repositories/atenea.git" \
  --arg runtimeRoot "${RUNTIME_ROOT}" \
  --arg logs "${LOGS_PATH}" \
  --arg artifacts "${ARTIFACTS_ROOT}" \
  --arg cache "${SESSION_CACHE}" '{
    schemaVersion: 1,
    sessionId: $session,
    projectId: "atenea",
    branch: ("atenea/session-" + $session),
    mirrorPath: $mirror,
    worktreePath: $worktree,
    runtimeId: $runtime,
    manifestRelativePath: "ops/atenea-runtime.json",
    slot: "slot3",
    heavyPermit: "heavy1",
    workloadClass: "heavy",
    state: "allocated",
    runtimeNames: {
      composeProject: ($runtime + "-compose"),
      network: ($runtime + "-network"),
      volumePrefix: ($runtime + "-volume"),
      processUnit: ("atenea-" + $runtime + ".service"),
      tomcatBase: ($runtimeRoot + "/tomcat")
    },
    runtimeRoot: $runtimeRoot,
    logsPath: $logs,
    artifactsRoot: $artifacts,
    cacheRoot: $cache,
    allocatedPorts: [
      {
        name: "codex",
        internalPort: 8092,
        protocol: "tcp",
        bindAddress: "127.0.0.1",
        loopbackPort: 28301
      },
      {
        name: "postgres",
        internalPort: 5432,
        protocol: "tcp",
        bindAddress: "127.0.0.1",
        loopbackPort: 28302
      },
      {
        name: "web",
        internalPort: 8081,
        protocol: "http",
        bindAddress: "127.0.0.1",
        loopbackPort: 28303
      }
    ]
  }' >"${ALLOCATION}"
chmod 0640 "${ALLOCATION}"

jq -n --arg session "${SESSION}" '{
  schemaVersion: 1,
  sessionId: $session,
  normal: {slot: "slot3", state: "held"},
  heavy: {permit: "heavy1", state: "held"}
}' >"${ADMISSION}"
chmod 0640 "${ADMISSION}"

cat >"${DOCKER}" <<'DOCKER'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"${ATENEA_TEST_DOCKER_LOG}"
exit 97
DOCKER
chmod 0750 "${DOCKER}"

cat >"${ENGINE_WRAPPER}" <<'WRAPPER'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >>"${ATENEA_TEST_ENGINE_LOG}"
if [[ "$1" == "execute" ]]; then
  [[ "$2" == "--plan" ]]
  cp "$3" "${ATENEA_CAPTURED_PLAN}"
  chmod 0600 "${ATENEA_CAPTURED_PLAN}"
  printf '{"state":"stopped","healthState":"stopped"}\n'
  exit 0
fi
[[ "$1" == "inspect" ]]
inspection="$("${ATENEA_REAL_ENGINE}" "$@")"
case "${ATENEA_NEGATIVE_MODE:-safe}" in
  safe) jq -c '.' <<<"${inspection}" ;;
  daemon-socket)
    jq -c '.services[1].daemonSockets = ["/var/run/docker.sock"]' <<<"${inspection}" ;;
  privileged)
    jq -c '.services[2].unsupportedFields = ["privileged"]' <<<"${inspection}" ;;
  host-network)
    jq -c '.services[2].namespaces = ["host-network"]' <<<"${inspection}" ;;
  host-pid)
    jq -c '.services[2].namespaces = ["host-pid"]' <<<"${inspection}" ;;
  host-ipc)
    jq -c '.services[2].namespaces = ["host-ipc"]' <<<"${inspection}" ;;
  device)
    jq -c '.services[2].devices = ["/dev/kvm"]' <<<"${inspection}" ;;
  undeclared-mount)
    jq -c '.services[2].mounts += [{
      type: "bind", source: "/etc", target: "/host-etc", readOnly: true
    }]' <<<"${inspection}" ;;
  fixed-container)
    jq -c '.services[0].containerName = "atenea-db"' <<<"${inspection}" ;;
  fixed-project)
    jq -c '.compose.projectName = "atenea"' <<<"${inspection}" ;;
  fixed-network)
    jq -c '.compose.network.name = "atenea-network"' <<<"${inspection}" ;;
  fixed-volume)
    jq -c '.compose.volumes[0].name = "atenea-db-data"' <<<"${inspection}" ;;
  foreign-resource)
    jq -c '.services[0].resourceNames += ["ws-foreign-network"]' <<<"${inspection}" ;;
  unlabelled)
    jq -c '.services[0].labels = {}' <<<"${inspection}" ;;
  partially-labelled)
    jq -c 'del(.services[0].labels["com.atenea.service"])' <<<"${inspection}" ;;
  foreign-labelled)
    jq -c '.services[0].labels["com.atenea.session"] =
      "018f47a2-6b0c-7a31-9c2d-4f5a6b7c8dab"' <<<"${inspection}" ;;
  ambiguous-services)
    jq -c '.services += [.services[0]]' <<<"${inspection}" ;;
  *) exit 64 ;;
esac
WRAPPER
chmod 0750 "${ENGINE_WRAPPER}"

engine_env=(
  ATENEA_RUNTIME_ENGINE_TEST_MODE=1
  ATENEA_RUNTIME_DOCKER_BIN="${DOCKER}"
  ATENEA_RUNTIME_DOCKER_HOST="unix://${TEST_ROOT}/docker.sock"
  ATENEA_RUNTIME_FAKE_DOCKER=1
  ATENEA_TEST_DOCKER_LOG="${DOCKER_LOG}"
  ATENEA_ENGINE_WORKSPACE_ROOT="${WORKSPACE_ROOT}"
  ATENEA_ENGINE_ARTIFACT_ROOT="${ARTIFACT_ROOT}"
  ATENEA_ENGINE_CACHE_ROOT="${CACHE_ROOT}"
)

run_manager() {
  env \
    ATENEA_RUNTIME_MANAGER_TEST_MODE=1 \
    ATENEA_WORKSPACE_ROOT="${WORKSPACE_ROOT}" \
    ATENEA_ARTIFACT_ROOT="${ARTIFACT_ROOT}" \
    ATENEA_CACHE_ROOT="${CACHE_ROOT}" \
    ATENEA_RUNTIME_MANAGER_CONTROL_ROOT="${CONTROL_ROOT}" \
    ATENEA_RUNTIME_ADMISSION_RECORD="${ADMISSION}" \
    ATENEA_RUNTIME_ENGINE="${ENGINE_WRAPPER}" \
    ATENEA_REAL_ENGINE="${ENGINE}" \
    ATENEA_TEST_ENGINE_LOG="${ENGINE_LOG}" \
    ATENEA_CAPTURED_PLAN="${BASE_PLAN}" \
    ATENEA_NEGATIVE_MODE="${ATENEA_NEGATIVE_MODE:-safe}" \
    "${engine_env[@]}" \
    "${MANAGER}" "$@"
}

manager_args=(
  --session "${SESSION}"
  --allocation "${ALLOCATION}"
  --manifest "${MANIFEST}"
  --json
)

run_manager status "${manager_args[@]}" >/dev/null
[[ -f "${BASE_PLAN}" ]] || fail "manager did not produce the baseline closed plan"
baseline_execute_count="$(grep -c '^execute ' "${ENGINE_LOG}")"

manager_modes=(
  daemon-socket privileged host-network host-pid host-ipc device
  undeclared-mount fixed-container fixed-project fixed-network fixed-volume
  foreign-resource unlabelled partially-labelled foreign-labelled
  ambiguous-services
)
for mode in "${manager_modes[@]}"; do
  export ATENEA_NEGATIVE_MODE="${mode}"
  expect_failure RUNTIME_OWNERSHIP_CONFLICT \
    run_manager up "${manager_args[@]}"
  [[ "$(grep -c '^execute ' "${ENGINE_LOG}")" == "${baseline_execute_count}" ]] ||
    fail "${mode} reached engine execution"
done
unset ATENEA_NEGATIVE_MODE

mutate_plan() {
  local mode="$1"
  case "${mode}" in
    daemon-socket)
      jq '.ateneaAdapter.services[1].daemonSockets =
        ["/var/run/docker.sock"]' "${BASE_PLAN}" ;;
    privileged)
      jq '.restrictions.noNewPrivileges = false' "${BASE_PLAN}" ;;
    host-network)
      jq '.restrictions.hostNetwork = true' "${BASE_PLAN}" ;;
    host-pid)
      jq '.restrictions.hostPid = true' "${BASE_PLAN}" ;;
    host-ipc)
      jq '.restrictions.hostIpc = true' "${BASE_PLAN}" ;;
    device)
      jq '.restrictions.devicesAllowed = true' "${BASE_PLAN}" ;;
    undeclared-mount)
      jq '.restrictions.mountsAllowed += ["/etc"]' "${BASE_PLAN}" ;;
    fixed-container)
      jq '.ateneaAdapter.services[0].containerName = "atenea-db"' "${BASE_PLAN}" ;;
    fixed-project)
      jq '.ateneaAdapter.compose.projectName = "atenea"' "${BASE_PLAN}" ;;
    fixed-network)
      jq '.ateneaAdapter.compose.network.name = "atenea-network"' "${BASE_PLAN}" ;;
    fixed-volume)
      jq '.ateneaAdapter.compose.volumes[0].name = "atenea-db-data"' "${BASE_PLAN}" ;;
    foreign-resource)
      jq '.ateneaAdapter.services[0].resourceNames +=
        ["ws-foreign-network"]' "${BASE_PLAN}" ;;
    unlabelled)
      jq '.ateneaAdapter.services[0].labels = {}' "${BASE_PLAN}" ;;
    partially-labelled)
      jq 'del(.ateneaAdapter.services[0].labels["com.atenea.service"])' \
        "${BASE_PLAN}" ;;
    foreign-labelled)
      jq '.ateneaAdapter.services[0].labels["com.atenea.session"] =
        "018f47a2-6b0c-7a31-9c2d-4f5a6b7c8dab"' "${BASE_PLAN}" ;;
    ambiguous-services)
      jq '.ateneaAdapter.services += [.ateneaAdapter.services[0]]' \
        "${BASE_PLAN}" ;;
    *) return 64 ;;
  esac >"${MUTATED_PLAN}"
  chmod 0600 "${MUTATED_PLAN}"
}

for mode in "${manager_modes[@]}"; do
  mutate_plan "${mode}"
  expect_failure RUNTIME_OWNERSHIP_CONFLICT \
    env "${engine_env[@]}" \
      "${ENGINE}" execute --plan "${MUTATED_PLAN}" --json
done

cp "${SOURCE_COMPOSE}" "${TEST_ROOT}/compose.backup"
for unsafe_field in \
  daemon_socket privileged network_mode_host pid_host ipc_host devices \
  undeclared_mount container_name fixed_network fixed_volume; do
  case "${unsafe_field}" in
    daemon_socket)
      sed '0,/^    volumes:$/{
        /^    volumes:$/a\      - /var/run/docker.sock:/var/run/docker.sock
      }' "${TEST_ROOT}/compose.backup" >"${COMPOSE}" ;;
    privileged)
      sed '0,/^  db:$/{
        /^  db:$/a\    privileged: true
      }' "${TEST_ROOT}/compose.backup" >"${COMPOSE}" ;;
    network_mode_host)
      sed '0,/^  db:$/{
        /^  db:$/a\    network_mode: host
      }' "${TEST_ROOT}/compose.backup" >"${COMPOSE}" ;;
    pid_host)
      sed '0,/^  db:$/{
        /^  db:$/a\    pid: host
      }' "${TEST_ROOT}/compose.backup" >"${COMPOSE}" ;;
    ipc_host)
      sed '0,/^  db:$/{
        /^  db:$/a\    ipc: host
      }' "${TEST_ROOT}/compose.backup" >"${COMPOSE}" ;;
    devices)
      sed '0,/^  db:$/{
        /^  db:$/a\    devices:\n      - /dev/kvm:/dev/kvm
      }' "${TEST_ROOT}/compose.backup" >"${COMPOSE}" ;;
    undeclared_mount)
      sed '0,/^    volumes:$/{
        /^    volumes:$/a\      - /etc:/host-etc:ro
      }' "${TEST_ROOT}/compose.backup" >"${COMPOSE}" ;;
    container_name)
      sed '0,/^  db:$/{
        /^  db:$/a\    container_name: atenea-db
      }' "${TEST_ROOT}/compose.backup" >"${COMPOSE}" ;;
    fixed_network)
      sed 's#name: ${ATENEA_SESSION_NETWORK_NAME:?required owned WorkSession network}#name: atenea-network#' \
        "${TEST_ROOT}/compose.backup" >"${COMPOSE}" ;;
    fixed_volume)
      sed 's#name: ${ATENEA_SESSION_DATABASE_VOLUME:?required new owned WorkSession PostgreSQL volume}#name: atenea-db-data#' \
        "${TEST_ROOT}/compose.backup" >"${COMPOSE}" ;;
  esac
  chmod 0640 "${COMPOSE}"
  expect_failure MANIFEST_INVALID \
    env "${engine_env[@]}" \
      "${ENGINE}" inspect \
        --session "${SESSION}" \
        --allocation "${ALLOCATION}" \
        --manifest "${MANIFEST}"
done
install -m 0640 "${TEST_ROOT}/compose.backup" "${COMPOSE}"

[[ ! -s "${DOCKER_LOG}" ]] ||
  fail "a rejected policy case called the container daemon"
if find "${CONTROL_ROOT}" -mindepth 1 -print -quit | grep -q .; then
  fail "negative manager cases left a control plan behind"
fi
if grep -R -E '(PASSWORD|TOKEN|SECRET|API_KEY)=' "${TEST_ROOT}" >/dev/null 2>&1; then
  fail "a literal secret assignment was persisted"
fi

printf 'Atenea runtime adapter v1 negative policy corpus passed.\n'
