#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
ENGINE_SOURCE="${SCRIPT_DIR}/runtime-engine-v1.sh"
MANAGER_SOURCE="${SCRIPT_DIR}/runtime-manager-v1.sh"
CLIENT_SOURCE="${SCRIPT_DIR}/runtime-client-v1.sh"
DEV="${SCRIPT_DIR}/dev-session-v1.sh"
TEST_ROOT="$(mktemp -d /tmp/atenea-runtime-engine-test.XXXXXX)"
LISTENER_PIDS=()

cleanup() {
  local pid
  for pid in "${LISTENER_PIDS[@]}"; do
    kill "${pid}" 2>/dev/null || true
    wait "${pid}" 2>/dev/null || true
  done
  case "${TEST_ROOT}" in
    /tmp/atenea-runtime-engine-test.*)
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
  local code="$1"
  shift
  local output
  if output="$("$@" 2>&1)"; then
    fail "command unexpectedly succeeded: $*"
  fi
  grep -q "^${code}:" <<<"${output}" ||
    fail "expected ${code}, got: ${output}"
}

WORKSPACE_ROOT="${TEST_ROOT}/workspaces"
ARTIFACT_ROOT="${TEST_ROOT}/artifacts"
CACHE_ROOT="${TEST_ROOT}/caches"
CONTROL_ROOT="${TEST_ROOT}/manager-control"
FAKE_STATE="${TEST_ROOT}/fake-docker"
ENGINE="${TEST_ROOT}/runtime-engine-v1"
MANAGER="${TEST_ROOT}/runtime-manager-v1"
CLIENT="${TEST_ROOT}/runtime-client-v1"
DOCKER="${TEST_ROOT}/docker"
mkdir -p \
  "${WORKSPACE_ROOT}/sessions" "${ARTIFACT_ROOT}" "${CACHE_ROOT}" \
  "${CONTROL_ROOT}" "${FAKE_STATE}/images" "${FAKE_STATE}/containers" \
  "${FAKE_STATE}/networks"
install -m 0750 "${ENGINE_SOURCE}" "${ENGINE}"
install -m 0750 "${MANAGER_SOURCE}" "${MANAGER}"
install -m 0750 "${CLIENT_SOURCE}" "${CLIENT}"

cat >"${DOCKER}" <<'DOCKER'
#!/usr/bin/env bash
set -Eeuo pipefail
state="${ATENEA_FAKE_DOCKER_STATE}"
printf '%s\n' "$*" >>"${state}/commands.log"
kind="$1"
shift

safe_name() {
  tr '/:' '__' <<<"$1"
}

labels() {
  local name="$1"
  cat "$2/$(safe_name "${name}").labels"
}

case "${kind}" in
  version)
    echo "fake rootless Docker 29.6.2"
    ;;
  build)
    tag=""
    while [[ "$#" -gt 0 ]]; do
      [[ "$1" == "--tag" ]] && tag="$2"
      shift
    done
    [[ -n "${tag}" ]]
    printf 'atenea-runtime-engine-v1 %s %s\n' \
      "${ATENEA_FAKE_SESSION}" "${ATENEA_FAKE_RUNTIME}" \
      >"${state}/images/$(safe_name "${tag}").labels"
    ;;
  image)
    action="$1"
    shift
    [[ "${action}" == "inspect" ]]
    format=""
    if [[ "${1:-}" == "--format" ]]; then
      format="$2"
      shift 2
    fi
    image="$1"
    [[ -f "${state}/images/$(safe_name "${image}").labels" ]] || exit 1
    if [[ "${format}" == *"fixture.build-jdk"* ]]; then
      echo "17.0.19 8 8.5.100"
    elif [[ -n "${format}" ]]; then
      labels "${image}" "${state}/images"
    else
      echo '{}'
    fi
    ;;
  container)
    action="$1"
    shift
    format=""
    if [[ "${1:-}" == "--format" ]]; then
      format="$2"
      shift 2
    fi
    name="$1"
    file="${state}/containers/$(safe_name "${name}")"
    case "${action}" in
      inspect)
        [[ -f "${file}.labels" ]] || exit 1
        if [[ "${format}" == *"State.Running"* ]]; then
          cat "${file}.running"
        elif [[ -n "${format}" ]]; then
          cat "${file}.labels"
        else
          echo '{}'
        fi
        ;;
      *)
        exit 64
        ;;
    esac
    ;;
  network)
    action="$1"
    shift
    case "${action}" in
      inspect)
        format=""
        if [[ "${1:-}" == "--format" ]]; then
          format="$2"
          shift 2
        fi
        name="$1"
        file="${state}/networks/$(safe_name "${name}").labels"
        [[ -f "${file}" ]] || exit 1
        [[ -z "${format}" ]] && echo '{}' || cat "${file}"
        ;;
      create)
        name="${@: -1}"
        printf 'atenea-runtime-engine-v1 %s %s\n' \
          "${ATENEA_FAKE_SESSION}" "${ATENEA_FAKE_RUNTIME}" \
          >"${state}/networks/$(safe_name "${name}").labels"
        echo fake-network
        ;;
      *)
        exit 64
        ;;
    esac
    ;;
  compose)
    project=""
    file=""
    while [[ "$#" -gt 0 ]]; do
      case "$1" in
        --project-name) project="$2"; shift 2 ;;
        --file) file="$2"; shift 2 ;;
        up) shift; break ;;
        *) shift ;;
      esac
    done
    name="$(jq -r '.services.app.container_name' "${file}")"
    network="$(jq -r '.networks.runtime.name' "${file}")"
    printf 'atenea-runtime-engine-v1 %s %s\n' \
      "${ATENEA_FAKE_SESSION}" "${ATENEA_FAKE_RUNTIME}" \
      >"${state}/containers/$(safe_name "${name}").labels"
    echo true >"${state}/containers/$(safe_name "${name}").running"
    printf 'atenea-runtime-engine-v1 %s %s\n' \
      "${ATENEA_FAKE_SESSION}" "${ATENEA_FAKE_RUNTIME}" \
      >"${state}/networks/$(safe_name "${network}").labels"
    ;;
  run)
    name=""
    network=""
    while [[ "$#" -gt 0 ]]; do
      case "$1" in
        --name) name="$2"; shift 2 ;;
        --network) network="$2"; shift 2 ;;
        *) shift ;;
      esac
    done
    printf 'atenea-runtime-engine-v1 %s %s\n' \
      "${ATENEA_FAKE_SESSION}" "${ATENEA_FAKE_RUNTIME}" \
      >"${state}/containers/$(safe_name "${name}").labels"
    echo true >"${state}/containers/$(safe_name "${name}").running"
    echo fake-container
    ;;
  start|restart)
    [[ "${1:-}" == "--time" ]] && shift 2
    name="$1"
    echo true >"${state}/containers/$(safe_name "${name}").running"
    echo "${name}"
    ;;
  stop)
    [[ "${1:-}" == "--time" ]] && shift 2
    name="$1"
    echo false >"${state}/containers/$(safe_name "${name}").running"
    echo "${name}"
    ;;
  rm)
    name="$1"
    rm -f \
      "${state}/containers/$(safe_name "${name}").labels" \
      "${state}/containers/$(safe_name "${name}").running"
    echo "${name}"
    ;;
  logs)
    echo "synthetic fixture log"
    ;;
  *)
    exit 64
    ;;
esac
DOCKER
chmod 0750 "${DOCKER}"

prepare_session() {
  local session="$1" project="$2" port="$3" fixture="$4" slot="${5:-slot2}"
  local session_root="${WORKSPACE_ROOT}/sessions/${session}"
  local worktree="${session_root}/${project}"
  local runtime="ws-${session//-/}"
  mkdir -p "${worktree}" \
    "${session_root}/runtime/${runtime}/tomcat" \
    "${ARTIFACT_ROOT}/sessions/${session}/runtime/logs" \
    "${ARTIFACT_ROOT}/sessions/${session}/runs" \
    "${CACHE_ROOT}/sessions/${session}"
  chmod 2770 "${session_root}/runtime/${runtime}"
  cp -a "${REPO_ROOT}/runtime-contract/fixtures/valid/${fixture}/." "${worktree}/"
  jq -n \
    --arg session "${session}" \
    --arg project "${project}" \
    --arg worktree "${worktree}" '{
      schemaVersion: 1,
      sessionId: $session,
      projectId: $project,
      branch: ("atenea/session-" + $session),
      mirrorPath: ("/tmp/synthetic/" + $project + ".git"),
      worktreePath: $worktree,
      state: "ready"
    }' >"${session_root}/workspace-v1.json"
  chmod 0640 "${session_root}/workspace-v1.json"
  jq -n \
    --arg session "${session}" \
    --arg project "${project}" \
    --arg worktree "${worktree}" \
    --arg runtime "${runtime}" \
    --argjson port "${port}" \
    --arg slot "${slot}" '{
      schemaVersion: 1,
      sessionId: $session,
      projectId: $project,
      branch: ("atenea/session-" + $session),
      mirrorPath: ("/tmp/synthetic/" + $project + ".git"),
      worktreePath: $worktree,
      runtimeId: $runtime,
      manifestRelativePath: "runtime.json",
      slot: $slot,
      workloadClass: "normal",
      state: "allocated",
      runtimeNames: {
        composeProject: ($runtime + "-compose"),
        network: ($runtime + "-network"),
        volumePrefix: ($runtime + "-volume"),
        processUnit: ("atenea-" + $runtime + ".service"),
        tomcatBase: (
          $worktree | sub("/[^/]+$"; "/runtime/" + $runtime + "/tomcat")
        )
      },
      runtimeRoot: (
        $worktree | sub("/[^/]+$"; "/runtime/" + $runtime)
      ),
      logsPath: (
        $worktree | sub(
          "/workspaces/sessions/[^/]+/[^/]+$";
          "/artifacts/sessions/" + $session + "/runtime/logs"
        )
      ),
      artifactsRoot: (
        $worktree | sub(
          "/workspaces/sessions/[^/]+/[^/]+$";
          "/artifacts/sessions/" + $session + "/runs"
        )
      ),
      cacheRoot: (
        $worktree | sub(
          "/workspaces/sessions/[^/]+/[^/]+$";
          "/caches/sessions/" + $session
        )
      ),
      allocatedPorts: [{
        name: "web",
        internalPort: 8080,
        protocol: "http",
        bindAddress: "127.0.0.1",
        loopbackPort: $port
      }]
    }' >"${session_root}/runtime-allocation-v1.json"
  chmod 0640 "${session_root}/runtime-allocation-v1.json"
}

SESSION_COMPOSE="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8d9e"
SESSION_TOMCAT="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8d9f"
prepare_session "${SESSION_COMPOSE}" dummy-compose 27101 dummy-compose slot2
prepare_session "${SESSION_TOMCAT}" dummy-tomcat 27102 dummy-tomcat slot3

for port in 27101 27102; do
  python3 -m http.server "${port}" --bind 127.0.0.1 \
    >"${TEST_ROOT}/http-${port}.log" 2>&1 &
  LISTENER_PIDS+=("$!")
done

run_dev() {
  local session="$1"
  shift
  local runtime="ws-${session//-/}"
  ATENEA_DEV_TEST_MODE=1 \
  ATENEA_WORKSPACE_ROOT="${WORKSPACE_ROOT}" \
  ATENEA_RUNTIME_CLIENT="${CLIENT}" \
  ATENEA_RUNTIME_CLIENT_TEST_MODE=1 \
  ATENEA_RUNTIME_MANAGER="${MANAGER}" \
  ATENEA_RUNTIME_MANAGER_TEST_MODE=1 \
  ATENEA_RUNTIME_MANAGER_CONTROL_ROOT="${CONTROL_ROOT}" \
  ATENEA_ARTIFACT_ROOT="${ARTIFACT_ROOT}" \
  ATENEA_CACHE_ROOT="${CACHE_ROOT}" \
  ATENEA_RUNTIME_ENGINE="${ENGINE}" \
  ATENEA_RUNTIME_ENGINE_TEST_MODE=1 \
  ATENEA_RUNTIME_DOCKER_BIN="${DOCKER}" \
  ATENEA_RUNTIME_DOCKER_HOST="unix://${TEST_ROOT}/docker.sock" \
  ATENEA_RUNTIME_FAKE_DOCKER=1 \
  ATENEA_FAKE_DOCKER_STATE="${FAKE_STATE}" \
  ATENEA_FAKE_SESSION="${session}" \
  ATENEA_FAKE_RUNTIME="${runtime}" \
  DO_NOT_EXPOSE_FIXTURE_SECRET="fixture-secret-48913" \
    "${DEV}" --session "${session}" "$@"
}

for session in "${SESSION_COMPOSE}" "${SESSION_TOMCAT}"; do
  project="$(jq -r '.projectId' \
    "${WORKSPACE_ROOT}/sessions/${session}/runtime-allocation-v1.json")"
  for operation in build up status logs restart redeploy; do
    output="$(run_dev "${session}" "${operation}" "${project}")"
    grep -Fq "fixture-secret-48913" <<<"${output}" &&
      fail "${operation} exposed an environment value"
    if [[ "${operation}" == "build" ]]; then
      engine_root="$(
        jq -r '.runtimeRoot' \
          "${WORKSPACE_ROOT}/sessions/${session}/runtime-allocation-v1.json"
      )/engine-v1"
      [[ "$(stat -c %a "${engine_root}")" == "700" ]] ||
        fail "engine state root inherited the parent setgid mode"
    fi
  done
  health_port="$(jq -r '.allocatedPorts[0].loopbackPort' \
    "${WORKSPACE_ROOT}/sessions/${session}/runtime-allocation-v1.json")"
  curl -fsS "http://127.0.0.1:${health_port}/" >/dev/null ||
    fail "${project} health endpoint is unavailable"
  json="$(run_dev "${session}" --json status)"
  jq -e '.state == "ready" and .health.state == "healthy"' <<<"${json}" >/dev/null ||
    fail "${project} JSON status is not healthy"
  [[ "$(run_dev "${session}" url)" == "http://127.0.0.1:${health_port}/" ]] ||
    fail "${project} URL did not use its loopback allocation"
  run_dev "${session}" stop "${project}" >/dev/null
  jq -e '.state == "stopped" and .health.state == "stopped"' \
    <<<"$(run_dev "${session}" --json status)" >/dev/null ||
    fail "${project} did not stop idempotently"
  run_dev "${session}" stop "${project}" >/dev/null
done

[[ "$(jq -r '.allocatedPorts[0].loopbackPort' \
  "${WORKSPACE_ROOT}/sessions/${SESSION_COMPOSE}/runtime-allocation-v1.json")" != \
  "$(jq -r '.allocatedPorts[0].loopbackPort' \
  "${WORKSPACE_ROOT}/sessions/${SESSION_TOMCAT}/runtime-allocation-v1.json")" ]] ||
  fail "same-port fixtures received one loopback port"

tomcat_artifact="${ARTIFACT_ROOT}/sessions/${SESSION_TOMCAT}/runs/fixture-build/toolchain.txt"
[[ "$(cat "${tomcat_artifact}")" == "17.0.19 8 8.5.100" ]] ||
  fail "Tomcat fixture did not retain its JDK 17/Java 8/Tomcat 8 evidence"

for session in "${SESSION_COMPOSE}" "${SESSION_TOMCAT}"; do
  [[ -f "${WORKSPACE_ROOT}/sessions/${session}/workspace-v1.json" &&
      -f "${WORKSPACE_ROOT}/sessions/${session}/runtime-allocation-v1.json" ]] ||
    fail "stop removed a WorkSession record"
  logs="${ARTIFACT_ROOT}/sessions/${session}/runtime/logs/runtime.log"
  [[ -f "${logs}" ]] || fail "stop did not retain session logs"
done

compose_manifest="${WORKSPACE_ROOT}/sessions/${SESSION_COMPOSE}/dummy-compose/runtime.json"
compose_allocation="${WORKSPACE_ROOT}/sessions/${SESSION_COMPOSE}/runtime-allocation-v1.json"
expect_failure SESSION_IDENTITY_CONFLICT \
  env \
    ATENEA_RUNTIME_CLIENT_TEST_MODE=1 \
    ATENEA_RUNTIME_MANAGER="${MANAGER}" \
    ATENEA_RUNTIME_MANAGER_TEST_MODE=1 \
    ATENEA_WORKSPACE_ROOT="${WORKSPACE_ROOT}" \
    ATENEA_ARTIFACT_ROOT="${ARTIFACT_ROOT}" \
    ATENEA_CACHE_ROOT="${CACHE_ROOT}" \
    ATENEA_RUNTIME_MANAGER_CONTROL_ROOT="${CONTROL_ROOT}" \
    ATENEA_RUNTIME_ENGINE="${ENGINE}" \
    "${CLIENT}" status \
      --session "${SESSION_TOMCAT}" \
      --allocation "${compose_allocation}" \
      --manifest "${compose_manifest}" --json

cp "${WORKSPACE_ROOT}/sessions/${SESSION_COMPOSE}/dummy-compose/compose.json" \
  "${TEST_ROOT}/compose.backup"
jq '.services.app.volumes = ["/:/host"]' "${TEST_ROOT}/compose.backup" \
  >"${WORKSPACE_ROOT}/sessions/${SESSION_COMPOSE}/dummy-compose/compose.json"
expect_failure OPERATION_FAILED run_dev "${SESSION_COMPOSE}" up dummy-compose
cp "${TEST_ROOT}/compose.backup" \
  "${WORKSPACE_ROOT}/sessions/${SESSION_COMPOSE}/dummy-compose/compose.json"

if grep -Fq "MANIFEST_ARGV_MUST_NOT_RUN" "${FAKE_STATE}/commands.log"; then
  fail "the engine executed or copied manifest argv"
fi
if grep -R -Fq "fixture-secret-48913" "${TEST_ROOT}" 2>/dev/null; then
  fail "the engine persisted an environment value"
fi

echo "Runtime engine v1 fixture tests passed."
