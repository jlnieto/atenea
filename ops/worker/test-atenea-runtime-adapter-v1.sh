#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MANAGER_SOURCE="${SCRIPT_DIR}/runtime-manager-v1.sh"
ENGINE_SOURCE="${SCRIPT_DIR}/runtime-engine-v1.sh"
SOURCE_ROOT="${ATENEA_RELOCATION_SOURCE_ROOT:-}"
TEST_ROOT="$(mktemp -d /tmp/atenea-runtime-adapter-test.XXXXXX)"

cleanup() {
  case "${TEST_ROOT}" in
    /tmp/atenea-runtime-adapter-test.*)
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
ADAPTER_SOURCE="${SCRIPT_DIR}/atenea-runtime-engine-adapter-v1.sh"
[[ -f "${SOURCE_MANIFEST}" && -f "${SOURCE_COMPOSE}" ]] ||
  fail "the exact Atenea manifest and AX42 Compose inputs are required"
[[ "$(sha256sum "${SOURCE_MANIFEST}" | cut -d' ' -f1)" == \
    "3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3" ]] ||
  fail "Atenea manifest input hash differs"
[[ "$(sha256sum "${SOURCE_COMPOSE}" | cut -d' ' -f1)" == \
    "2133646b9fe6227ca417d6d62c92a74306caaa46a2957cdee810d5d7b0e5bb9f" ]] ||
  fail "Atenea Compose input hash differs"
grep -Fqx \
  '      ATENEA_MOBILE_UPLOAD_ROOT: /workspace/data/uploads' \
  "${SOURCE_COMPOSE}" ||
  fail "Atenea Compose does not bind mobile uploads to the owned runtime path"
for required in \
  'npm ci --prefer-offline --no-audit' \
  'npm run build' \
  'SPRING_DATASOURCE_URL=jdbc:postgresql://${BUILD_DB_CONTAINER}:5432/atenea_test' \
  'ATENEA_WORKSPACE_ROOT=/workspace/repos' \
  '--tmpfs /workspace/repos:rw,nosuid,nodev,size=512m' \
  'ensure_retained_volume' \
  'ensure_runtime_secrets' \
  "'48|1|48|48|0|48'" \
  'mvn -B -Dmaven.repo.local=/workspace/cache/maven/repository clean package'; do
  grep -Fq -- "${required}" "${ADAPTER_SOURCE}" ||
    fail "Atenea build adapter omits required command: ${required}"
done
if grep -Fq -- '-DskipTests' "${ADAPTER_SOURCE}"; then
  fail "Atenea build adapter skips the complete backend test suite"
fi

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
ATENEA_ADAPTER="${TEST_ROOT}/atenea-runtime-engine-adapter-v1"
ATENEA_ADAPTER_LOG="${TEST_ROOT}/atenea-adapter.log"
DOCKER="${TEST_ROOT}/docker"
DOCKER_LOG="${TEST_ROOT}/docker.log"
ENGINE_LOG="${TEST_ROOT}/engine.log"
CAPTURED_PLAN="${TEST_ROOT}/runtime-plan-v1.json"

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
if [[ "$1" == "inspect" ]]; then
  exec "${ATENEA_REAL_ENGINE}" "$@"
fi
[[ "$1" == "execute" ]]
shift
[[ "$1" == "--plan" ]]
cp "$2" "${ATENEA_CAPTURED_PLAN}"
chmod 0600 "${ATENEA_CAPTURED_PLAN}"
printf '{"state":"stopped","healthState":"stopped"}\n'
WRAPPER
chmod 0750 "${ENGINE_WRAPPER}"

{
  printf '%s\n' \
    '#!/usr/bin/env bash' \
    'printf "%s\n" "$*" >>"${ATENEA_TEST_ADAPTER_LOG}"' \
    'printf '\''{"state":"stopped","healthState":"stopped"}\n'\'''
} >"${ATENEA_ADAPTER}"
chmod 0750 "${ATENEA_ADAPTER}"

engine_env=(
  ATENEA_RUNTIME_ENGINE_TEST_MODE=1
  ATENEA_RUNTIME_DOCKER_BIN="${DOCKER}"
  ATENEA_RUNTIME_DOCKER_HOST="unix://${TEST_ROOT}/docker.sock"
  ATENEA_RUNTIME_FAKE_DOCKER=1
  ATENEA_RUNTIME_ALLOWED_SLOT=slot3
  ATENEA_RUNTIME_ATENEA_ADAPTER="${ATENEA_ADAPTER}"
  ATENEA_RUNTIME_DELIVERY_BASE="${TEST_ROOT}/delivery"
  ATENEA_TEST_ADAPTER_LOG="${ATENEA_ADAPTER_LOG}"
  ATENEA_TEST_DOCKER_LOG="${DOCKER_LOG}"
  ATENEA_ENGINE_WORKSPACE_ROOT="${WORKSPACE_ROOT}"
  ATENEA_ENGINE_ARTIFACT_ROOT="${ARTIFACT_ROOT}"
  ATENEA_ENGINE_CACHE_ROOT="${CACHE_ROOT}"
)

inspection="$(
  env "${engine_env[@]}" \
    "${ENGINE}" inspect \
      --session "${SESSION}" \
      --allocation "${ALLOCATION}" \
      --manifest "${MANIFEST}"
)"
jq -e \
  --arg session "${SESSION}" \
  --arg runtime "${RUNTIME}" '
    .schemaVersion == 1 and .sessionId == $session and
    .runtimeId == $runtime and .projectId == "atenea" and .slot == "slot3" and
    ([.services[].name] | sort) == ["atenea-dev", "codex-app-server", "db"] and
    ([.services[].ports[].internalPort] | sort) == [5432, 8081, 8092] and
    all(.services[].ports[]; .bindAddress == "127.0.0.1") and
    all(.services[];
      .labels["com.atenea.engine"] == "atenea-runtime-engine-v1" and
      .labels["com.atenea.session"] == $session and
      .labels["com.atenea.runtime"] == $runtime and
      .labels["com.atenea.project"] == "atenea" and
      .labels["com.atenea.service"] == .name and
      .namespaces == [] and .capabilities == [] and .devices == [] and
      .daemonSockets == [] and .unsupportedFields == []) and
    .compose.projectName == ($runtime + "-compose") and
    .compose.network.name == ($runtime + "-network") and
    .compose.network.internal == true and
    .compose.volumes[0].name == ($runtime + "-volume-db-data")
  ' <<<"${inspection}" >/dev/null ||
  fail "engine inspection does not match the exact Atenea adapter"

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
    ATENEA_CAPTURED_PLAN="${CAPTURED_PLAN}" \
    "${engine_env[@]}" \
    "${MANAGER}" "$@"
}

manager_output="$(
  run_manager status \
    --session "${SESSION}" \
    --allocation "${ALLOCATION}" \
    --manifest "${MANIFEST}" \
    --json
)"
jq -e '.state == "stopped" and .healthState == "stopped"' \
  <<<"${manager_output}" >/dev/null ||
  fail "manager did not accept the exact synthetic Atenea records"
[[ -f "${CAPTURED_PLAN}" ]] || fail "manager did not produce a closed plan"
jq -e \
  --argjson inspection "${inspection}" '
    .projectId == "atenea" and .slot == "slot3" and
    .ateneaAdapter == $inspection and
    .allocatedPorts == ($inspection.services | map(.ports[]) | sort_by(.name)) and
    .restrictions.mountsAllowed == (
      [.ateneaAdapter.services[].mounts[] |
        select(.type == "bind") | .source] | unique | sort
    ) and
    .restrictions.secretRefsAllowed == [
      "ATENEA_DEV_JWT_SECRET",
      "ATENEA_DEV_POSTGRES_PASSWORD"
    ]
  ' "${CAPTURED_PLAN}" >/dev/null ||
  fail "manager plan is not the exact engine-inspected Atenea plan"
if grep -Fq "atenea-runtime-v1.sh" "${CAPTURED_PLAN}"; then
  fail "manager copied manifest argv into the engine plan"
fi

engine_output="$(
  env "${engine_env[@]}" \
    "${ENGINE}" execute --plan "${CAPTURED_PLAN}" --json
)"
jq -e '.state == "stopped" and .healthState == "stopped"' \
  <<<"${engine_output}" >/dev/null ||
  fail "the validated Atenea plan did not reach the fixed adapter"
grep -Fq "execute --plan ${CAPTURED_PLAN} --docker-host unix://${TEST_ROOT}/docker.sock --json" \
  "${ATENEA_ADAPTER_LOG}" ||
  fail "the engine did not delegate the closed plan to the fixed Atenea adapter"
[[ ! -s "${DOCKER_LOG}" ]] ||
  fail "Atenea plan validation or adapter delegation called the fake container daemon"

cp "${SOURCE_MANIFEST}" "${MANIFEST}"
printf '\n' >>"${MANIFEST}"
engine_count="$(wc -l <"${ENGINE_LOG}")"
expect_failure MANIFEST_INVALID \
  run_manager status \
    --session "${SESSION}" --allocation "${ALLOCATION}" \
    --manifest "${MANIFEST}" --json
[[ "$(wc -l <"${ENGINE_LOG}")" == "${engine_count}" ]] ||
  fail "wrong manifest hash reached the engine"
install -m 0640 "${SOURCE_MANIFEST}" "${MANIFEST}"

printf '\n' >>"${COMPOSE}"
expect_failure MANIFEST_INVALID \
  run_manager status \
    --session "${SESSION}" --allocation "${ALLOCATION}" \
    --manifest "${MANIFEST}" --json
[[ "$(wc -l <"${ENGINE_LOG}")" == "${engine_count}" ]] ||
  fail "wrong Compose hash reached the engine"
install -m 0640 "${SOURCE_COMPOSE}" "${COMPOSE}"

cp "${ALLOCATION}" "${TEST_ROOT}/allocation.backup"
jq --arg foreign "${TEST_ROOT}/foreign/atenea" '.worktreePath = $foreign' \
  "${TEST_ROOT}/allocation.backup" >"${ALLOCATION}"
chmod 0640 "${ALLOCATION}"
expect_failure RECONCILIATION_REQUIRED \
  run_manager status \
    --session "${SESSION}" --allocation "${ALLOCATION}" \
    --manifest "${MANIFEST}" --json
install -m 0640 "${TEST_ROOT}/allocation.backup" "${ALLOCATION}"

jq '.normal.slot = "slot4"' "${ADMISSION}" >"${TEST_ROOT}/admission.foreign"
install -m 0640 "${TEST_ROOT}/admission.foreign" "${ADMISSION}"
expect_failure RUNTIME_OWNERSHIP_CONFLICT \
  run_manager status \
    --session "${SESSION}" --allocation "${ALLOCATION}" \
    --manifest "${MANIFEST}" --json

[[ ! -s "${DOCKER_LOG}" ]] ||
  fail "a rejected Atenea identity called the container daemon"
if grep -R -Fq "SHOULD_NOT_EXECUTE_MANIFEST_ARGV" "${TEST_ROOT}" 2>/dev/null; then
  fail "a manifest argv marker was executed or persisted"
fi

printf 'Atenea runtime adapter v1 positive validation passed.\n'
