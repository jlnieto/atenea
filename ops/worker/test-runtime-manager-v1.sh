#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MANAGER_SOURCE="${SCRIPT_DIR}/runtime-manager-v1.sh"
CLIENT_SOURCE="${SCRIPT_DIR}/runtime-client-v1.sh"
ALLOCATOR="${SCRIPT_DIR}/session-runtime-allocation-v1.sh"
DEV="${SCRIPT_DIR}/dev-session-v1.sh"
TEST_ROOT="$(mktemp -d /tmp/atenea-runtime-manager-test.XXXXXX)"

cleanup() {
  case "${TEST_ROOT}" in
    /tmp/atenea-runtime-manager-test.*)
      chmod -R u+w "${TEST_ROOT}" 2>/dev/null || true
      rm -rf -- "${TEST_ROOT}"
      ;;
  esac
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*" >&2
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

WORKSPACE_ROOT="${TEST_ROOT}/workspaces"
ARTIFACT_ROOT="${TEST_ROOT}/artifacts"
CACHE_ROOT="${TEST_ROOT}/caches"
ALLOCATION_CONTROL_ROOT="${TEST_ROOT}/allocation-control"
MANAGER_CONTROL_ROOT="${TEST_ROOT}/manager-control"
MIRROR_ROOT="${TEST_ROOT}/repositories"
MANAGER="${TEST_ROOT}/runtime-manager-v1"
CLIENT="${TEST_ROOT}/runtime-client-v1"
ENGINE="${TEST_ROOT}/runtime-engine-v1"
ENGINE_LOG="${TEST_ROOT}/engine.log"
mkdir -p \
  "${WORKSPACE_ROOT}" "${ARTIFACT_ROOT}" "${CACHE_ROOT}" \
  "${ALLOCATION_CONTROL_ROOT}" "${MANAGER_CONTROL_ROOT}" "${MIRROR_ROOT}"
install -m 0750 "${MANAGER_SOURCE}" "${MANAGER}"
install -m 0750 "${CLIENT_SOURCE}" "${CLIENT}"

cat >"${ENGINE}" <<'ENGINE'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >>"${ATENEA_TEST_ENGINE_LOG}"
action="$1"
shift

argument() {
  local name="$1"
  shift
  while [[ "$#" -gt 0 ]]; do
    if [[ "$1" == "${name}" ]]; then
      printf '%s\n' "$2"
      return
    fi
    shift
  done
  return 1
}

case "${action}" in
  inspect)
    allocation="$(argument --allocation "$@")"
    session="$(jq -r '.sessionId' "${allocation}")"
    runtime="$(jq -r '.runtimeId' "${allocation}")"
    compose="$(jq -r '.runtimeNames.composeProject' "${allocation}")"
    network="$(jq -r '.runtimeNames.network' "${allocation}")"
    volume="$(jq -r '.runtimeNames.volumePrefix' "${allocation}")"
    mode="${ATENEA_TEST_POLICY_MODE:-safe}"
    mounts='[]'
    namespaces='[]'
    capabilities='[]'
    devices='[]'
    sockets='[]'
    unsupported='[]'
    resources="$(
      jq -cn \
        --arg compose "${compose}" \
        --arg network "${network}" \
        --arg volume "${volume}-data" \
        '[$compose, $network, $volume]'
    )"
    case "${mode}" in
      safe)
        ;;
      mount)
        mounts='["/host:/container"]'
        ;;
      namespace)
        namespaces='["host-network"]'
        ;;
      capability)
        capabilities='["SYS_ADMIN"]'
        ;;
      device)
        devices='["/dev/kvm"]'
        ;;
      socket)
        sockets='["/var/run/docker.sock"]'
        ;;
      unsupported)
        unsupported='["cgroup_parent"]'
        ;;
      foreign)
        resources='["ws-ffffffffffffffffffffffffffffffff-network"]'
        ;;
      identity)
        session="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8da0"
        ;;
      inspect-fail)
        printf 'raw-inspect-%s\n' "${ATENEA_TEST_SECRET_MARKER}" >&2
        exit 70
        ;;
      *)
        exit 64
        ;;
    esac
    jq -cn \
      --arg session "${session}" \
      --arg runtime "${runtime}" \
      --argjson mounts "${mounts}" \
      --argjson namespaces "${namespaces}" \
      --argjson capabilities "${capabilities}" \
      --argjson devices "${devices}" \
      --argjson sockets "${sockets}" \
      --argjson unsupported "${unsupported}" \
      --argjson resources "${resources}" '{
        schemaVersion: 1,
        sessionId: $session,
        runtimeId: $runtime,
        services: [
          {
            name: "app",
            mounts: $mounts,
            namespaces: $namespaces,
            capabilities: $capabilities,
            devices: $devices,
            daemonSockets: $sockets,
            resourceNames: $resources,
            unsupportedFields: $unsupported
          },
          {
            name: "db",
            mounts: [],
            namespaces: [],
            capabilities: [],
            devices: [],
            daemonSockets: [],
            resourceNames: $resources,
            unsupportedFields: []
          }
        ]
      }'
    ;;
  execute)
    plan="$(argument --plan "$@")"
    jq -e '
      .schemaVersion == 1 and
      .sessionId == "018f47a2-6b0c-7a31-9c2d-4f5a6b7c8d9e" and
      .runtimeId == "ws-018f47a26b0c7a319c2d4f5a6b7c8d9e" and
      .restrictions == {
        noNewPrivileges: true,
        readOnlyRootFilesystem: true,
        dropAllCapabilities: true,
        hostNetwork: false,
        hostPid: false,
        hostIpc: false,
        devicesAllowed: false,
        daemonSocketsAllowed: false,
        mountsAllowed: []
      } and
      all(.allocatedPorts[]; .bindAddress == "127.0.0.1") and
      (.manifestPath | startswith("/tmp/"))
    ' "${plan}" >/dev/null
    if grep -q "SHOULD_NOT_EXECUTE_MANIFEST_ARGV" "${plan}"; then
      echo "manifest argv leaked into the execution plan" >&2
      exit 71
    fi
    if [[ "${ATENEA_TEST_POLICY_MODE:-safe}" == "execute-fail" ]]; then
      printf 'raw-execute-%s\n' "${ATENEA_TEST_SECRET_MARKER}"
      printf 'raw-execute-diagnostic-%s\n' "${ATENEA_TEST_SECRET_MARKER}" >&2
      exit 70
    fi
    operation="$(jq -r '.operation' "${plan}")"
    json=false
    for item in "$@"; do
      [[ "${item}" == "--json" ]] && json=true
    done
    if [[ "${json}" == "true" ]]; then
      case "${operation}" in
        stop)
          printf '{"state":"stopped","healthState":"stopped"}\n'
          ;;
        status|up|restart|redeploy|doctor)
          printf '{"state":"ready","healthState":"healthy"}\n'
          ;;
        build|logs)
          printf '{"state":"ready","healthState":"unknown"}\n'
          ;;
        *)
          exit 64
          ;;
      esac
    elif [[ "${operation}" == "logs" ]]; then
      echo "synthetic mediated log line"
    else
      printf 'Synthetic mediated operation: %s\n' "${operation}"
    fi
    ;;
  *)
    exit 64
    ;;
esac
ENGINE
chmod 0750 "${ENGINE}"

SESSION="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8d9e"
PROJECT="dummy-compose"
SESSION_ROOT="${WORKSPACE_ROOT}/sessions/${SESSION}"
WORKTREE="${SESSION_ROOT}/${PROJECT}"
MIRROR="${MIRROR_ROOT}/${PROJECT}.git"
MANIFEST="${WORKTREE}/ops/atenea-runtime.json"
mkdir -p "${WORKTREE}/ops" "${MIRROR}"
printf 'preserve manager worktree\n' >"${WORKTREE}/uncommitted.txt"
printf 'preserve manager mirror\n' >"${MIRROR}/synthetic-ref"
jq -n \
  --arg session "${SESSION}" \
  --arg project "${PROJECT}" \
  --arg mirror "${MIRROR}" \
  --arg worktree "${WORKTREE}" '{
    schemaVersion: 1,
    sessionId: $session,
    projectId: $project,
    branch: ("atenea/session-" + $session),
    mirrorPath: $mirror,
    worktreePath: $worktree,
    state: "ready"
  }' >"${SESSION_ROOT}/workspace-v1.json"
chmod 0640 "${SESSION_ROOT}/workspace-v1.json"

jq -n \
  --arg project "${PROJECT}" '{
    schemaVersion: 1,
    project: {
      id: $project,
      repository: {
        url: "https://github.com/example/dummy-compose.git",
        defaultBranch: "main"
      }
    },
    toolchains: [
      {name: "docker", version: "29.6.2", source: "worker-package"}
    ],
    runtime: {
      kind: "compose",
      composeFiles: ["compose.json"],
      services: ["app", "db"],
      internalPorts: [
        {name: "web", port: 8080, protocol: "http"}
      ]
    },
    lifecycle: {
      build: {
        argv: ["SHOULD_NOT_EXECUTE_MANIFEST_ARGV", "build"],
        timeoutSeconds: 30
      },
      start: {
        argv: ["SHOULD_NOT_EXECUTE_MANIFEST_ARGV", "up"],
        timeoutSeconds: 30
      },
      stop: {
        argv: ["SHOULD_NOT_EXECUTE_MANIFEST_ARGV", "stop"],
        timeoutSeconds: 30
      },
      health: {
        argv: ["SHOULD_NOT_EXECUTE_MANIFEST_ARGV", "health"],
        timeoutSeconds: 30
      },
      logs: {
        argv: ["SHOULD_NOT_EXECUTE_MANIFEST_ARGV", "logs"],
        timeoutSeconds: 30
      }
    },
    preview: {
      internalPort: "web",
      path: "/ready",
      publish: "private",
      command: {
        argv: ["SHOULD_NOT_EXECUTE_MANIFEST_ARGV", "preview"],
        timeoutSeconds: 30
      },
      localhostCompatibilityRequired: false
    },
    browserChecks: [
      {
        name: "home",
        path: "/",
        command: {
          argv: ["SHOULD_NOT_EXECUTE_MANIFEST_ARGV", "browser"],
          timeoutSeconds: 30
        },
        viewports: ["desktop-1440x900"],
        artifact: "artifacts/browser/home"
      }
    ],
    artifacts: [
      {name: "browser", path: "artifacts/browser", retention: "session"}
    ],
    secrets: [],
    workloadClass: "normal"
  }' >"${MANIFEST}"
printf '{"services":{"app":{"image":"example/app"},"db":{"image":"example/db"}}}\n' \
  >"${WORKTREE}/compose.json"

ATENEA_RUNTIME_TEST_MODE=1 \
ATENEA_WORKSPACE_ROOT="${WORKSPACE_ROOT}" \
ATENEA_ARTIFACT_ROOT="${ARTIFACT_ROOT}" \
ATENEA_CACHE_ROOT="${CACHE_ROOT}" \
ATENEA_RUNTIME_CONTROL_ROOT="${ALLOCATION_CONTROL_ROOT}" \
ATENEA_RUNTIME_PORT_START=26000 \
ATENEA_RUNTIME_PORT_END=26199 \
  "${ALLOCATOR}" ensure "${SESSION}" slot2 "${MANIFEST}" \
  >"${TEST_ROOT}/allocation.json"

ALLOCATION="${SESSION_ROOT}/runtime-allocation-v1.json"
LOGS_PATH="$(jq -r '.logsPath' "${ALLOCATION}")"
ARTIFACTS_ROOT="$(jq -r '.artifactsRoot' "${ALLOCATION}")"
printf 'preserve manager log\n' >"${LOGS_PATH}/runtime.log"
printf 'preserve manager artifact\n' >"${ARTIFACTS_ROOT}/result.txt"
allocation_hash="$(sha256sum "${ALLOCATION}" | cut -d' ' -f1)"
workspace_hash="$(sha256sum "${SESSION_ROOT}/workspace-v1.json" | cut -d' ' -f1)"
manifest_hash="$(sha256sum "${MANIFEST}" | cut -d' ' -f1)"
worktree_hash="$(sha256sum "${WORKTREE}/uncommitted.txt" | cut -d' ' -f1)"
mirror_hash="$(sha256sum "${MIRROR}/synthetic-ref" | cut -d' ' -f1)"
log_hash="$(sha256sum "${LOGS_PATH}/runtime.log" | cut -d' ' -f1)"
artifact_hash="$(sha256sum "${ARTIFACTS_ROOT}/result.txt" | cut -d' ' -f1)"

run_client() {
  ATENEA_RUNTIME_CLIENT_TEST_MODE=1 \
  ATENEA_RUNTIME_MANAGER="${MANAGER}" \
  ATENEA_RUNTIME_MANAGER_TEST_MODE=1 \
  ATENEA_WORKSPACE_ROOT="${WORKSPACE_ROOT}" \
  ATENEA_ARTIFACT_ROOT="${ARTIFACT_ROOT}" \
  ATENEA_CACHE_ROOT="${CACHE_ROOT}" \
  ATENEA_RUNTIME_MANAGER_CONTROL_ROOT="${MANAGER_CONTROL_ROOT}" \
  ATENEA_RUNTIME_ENGINE="${ENGINE}" \
  ATENEA_TEST_ENGINE_LOG="${ENGINE_LOG}" \
  ATENEA_TEST_POLICY_MODE="${ATENEA_TEST_POLICY_MODE:-safe}" \
  ATENEA_TEST_SECRET_MARKER="${ATENEA_TEST_SECRET_MARKER:-}" \
    "${CLIENT}" "$@"
}

manager_args=(
  --session "${SESSION}"
  --allocation "${ALLOCATION}"
  --manifest "${MANIFEST}"
)

for operation in status doctor build up stop restart redeploy logs; do
  output="$(run_client "${operation}" "${manager_args[@]}" --json)"
  jq -e -s '
    length == 1 and
    (.[0] | keys | sort) == ["healthState", "state"]
  ' <<<"${output}" >/dev/null ||
    fail "${operation} did not return one minimal structured engine result"
done

human_logs="$(run_client logs "${manager_args[@]}" --tail 37)"
[[ "${human_logs}" == "synthetic mediated log line" ]] ||
  fail "human logs did not pass through the mediated engine"
grep -q "execute --plan .*" "${ENGINE_LOG}" ||
  fail "runtime manager did not use its generated plan"

for mode in mount namespace capability device socket unsupported foreign identity; do
  export ATENEA_TEST_POLICY_MODE="${mode}"
  expect_failure RUNTIME_OWNERSHIP_CONFLICT \
    run_client up "${manager_args[@]}" --json
done
unset ATENEA_TEST_POLICY_MODE

cp "${MANIFEST}" "${TEST_ROOT}/manifest.backup"
for field in privileged mounts hostNetwork capabilities devices; do
  jq --arg field "${field}" '.runtime[$field] = true' \
    "${TEST_ROOT}/manifest.backup" >"${MANIFEST}"
  expect_failure MANIFEST_INVALID \
    run_client up "${manager_args[@]}" --json
done
cp "${TEST_ROOT}/manifest.backup" "${MANIFEST}"

chmod 0666 "${ALLOCATION}"
expect_failure RUNTIME_OWNERSHIP_CONFLICT \
  run_client status "${manager_args[@]}" --json
chmod 0640 "${ALLOCATION}"

expect_failure SESSION_IDENTITY_CONFLICT \
  run_client status \
    --session "018f47a2-6b0c-7a31-9c2d-4f5a6b7c8da0" \
    --allocation "${ALLOCATION}" \
    --manifest "${MANIFEST}" \
    --json

SECRET_MARKER="DO_NOT_EXPOSE_RUNTIME_MANAGER_SECRET_91357"
export ATENEA_TEST_SECRET_MARKER="${SECRET_MARKER}"
export ATENEA_TEST_POLICY_MODE=execute-fail
error_output="$(
  run_client up "${manager_args[@]}" --json 2>&1 || true
)"
grep -q "^OPERATION_FAILED:" <<<"${error_output}" ||
  fail "engine failure did not return a fixed manager error"
if grep -q "${SECRET_MARKER}" <<<"${error_output}"; then
  fail "manager exposed raw engine output or diagnostics"
fi
unset ATENEA_TEST_POLICY_MODE

run_dev() {
  ATENEA_DEV_TEST_MODE=1 \
  ATENEA_WORKSPACE_ROOT="${WORKSPACE_ROOT}" \
  ATENEA_RUNTIME_CLIENT="${CLIENT}" \
  ATENEA_RUNTIME_CLIENT_TEST_MODE=1 \
  ATENEA_RUNTIME_MANAGER="${MANAGER}" \
  ATENEA_RUNTIME_MANAGER_TEST_MODE=1 \
  ATENEA_RUNTIME_MANAGER_CONTROL_ROOT="${MANAGER_CONTROL_ROOT}" \
  ATENEA_ARTIFACT_ROOT="${ARTIFACT_ROOT}" \
  ATENEA_CACHE_ROOT="${CACHE_ROOT}" \
  ATENEA_RUNTIME_ENGINE="${ENGINE}" \
  ATENEA_TEST_ENGINE_LOG="${ENGINE_LOG}" \
    "${DEV}" "$@"
}

dev_json="$(run_dev --json --session "${SESSION}" up "${PROJECT}")"
jq -e \
  --arg session "${SESSION}" \
  --arg project "${PROJECT}" '
    .schemaVersion == 1 and .operation == "up" and .state == "ready" and
    .sessionId == $session and .projectId == $project and
    .health.state == "healthy" and
    (.url | startswith("http://127.0.0.1:"))
  ' <<<"${dev_json}" >/dev/null ||
  fail "dev JSON did not integrate with the mediated runtime manager"
dev_human="$(run_dev --session "${SESSION}" logs)"
grep -q "synthetic mediated log line" <<<"${dev_human}" ||
  fail "human dev logs did not integrate with the mediated manager"

run_client status "${manager_args[@]}" --json >/dev/null

[[ "$(sha256sum "${ALLOCATION}" | cut -d' ' -f1)" == "${allocation_hash}" &&
    "$(sha256sum "${SESSION_ROOT}/workspace-v1.json" | cut -d' ' -f1)" == "${workspace_hash}" &&
    "$(sha256sum "${MANIFEST}" | cut -d' ' -f1)" == "${manifest_hash}" &&
    "$(sha256sum "${WORKTREE}/uncommitted.txt" | cut -d' ' -f1)" == "${worktree_hash}" &&
    "$(sha256sum "${MIRROR}/synthetic-ref" | cut -d' ' -f1)" == "${mirror_hash}" &&
    "$(sha256sum "${LOGS_PATH}/runtime.log" | cut -d' ' -f1)" == "${log_hash}" &&
    "$(sha256sum "${ARTIFACTS_ROOT}/result.txt" | cut -d' ' -f1)" == "${artifact_hash}" ]] ||
  fail "runtime manager modified authoritative or retained synthetic state"

if find "${MANAGER_CONTROL_ROOT}" -mindepth 1 -print -quit | grep -q .; then
  fail "runtime manager left a control plan behind"
fi
if [[ -e "${TEST_ROOT}/SHOULD_NOT_EXECUTE_MANIFEST_ARGV" ]]; then
  fail "runtime manager executed a manifest argv directly"
fi

echo "Runtime manager v1 tests passed."
