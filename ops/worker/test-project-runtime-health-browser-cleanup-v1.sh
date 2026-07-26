#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
CLEANUP="${SCRIPT_DIR}/runtime-cleanup-v1.sh"
BROWSER_CHECK="${SCRIPT_DIR}/project-runtime-browser-check-v1.js"
BROWSER_RUNNER="${ATENEA_BROWSER_RUNNER:-}"
TEST_ROOT="$(mktemp -d /tmp/atenea-runtime-health-browser-cleanup-test.XXXXXX)"
VISUAL_ROOT="${ATENEA_VISUAL_ROOT:-/tmp/codex-visual-checks/remote-codex-platform}"
PIDS=()
SECRET_MARKER="DO_NOT_EXPOSE_BROWSER_CLEANUP_SECRET_73192"

cleanup_test_root() {
  local pid
  for pid in "${PIDS[@]}"; do
    kill "${pid}" 2>/dev/null || true
    wait "${pid}" 2>/dev/null || true
  done
  case "${TEST_ROOT}" in
    /tmp/atenea-runtime-health-browser-cleanup-test.*)
      chmod -R u+w "${TEST_ROOT}" 2>/dev/null || true
      rm -rf -- "${TEST_ROOT}"
      ;;
  esac
}
trap cleanup_test_root EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

[[ -x "${BROWSER_RUNNER}" && "${BROWSER_RUNNER}" == /tmp/* &&
    ! -L "${BROWSER_RUNNER}" ]] ||
  fail "ATENEA_BROWSER_RUNNER must be an executable synthetic wrapper beneath /tmp"
for command in curl jq python3 sha256sum; do
  command -v "${command}" >/dev/null || fail "missing ${command}"
done

FAKE_STATE="${TEST_ROOT}/fake-docker"
mkdir -p "${FAKE_STATE}"/{containers,networks,images}
FAKE_DOCKER="${TEST_ROOT}/docker"
cat >"${FAKE_DOCKER}" <<'DOCKER'
#!/usr/bin/env bash
set -Eeuo pipefail
root="${ATENEA_FAKE_DOCKER_STATE}"
kind="$1"; shift
safe() { tr '/:' '__' <<<"$1"; }
case "${kind}" in
  container|image|network)
    action="$1"; shift
    format=""
    if [[ "${1:-}" == "--format" ]]; then format="$2"; shift 2; fi
    object="${@: -1}"
    dir="${root}/${kind}s"
    file="${dir}/$(safe "${object}")"
    case "${action}" in
      inspect)
        [[ -f "${file}.labels" ]] || exit 1
        [[ -n "${format}" ]] && cat "${file}.labels" || echo '{}'
        ;;
      rm)
        rm -f "${file}.labels"
        ;;
      *) exit 64 ;;
    esac
    ;;
  rm)
    [[ "${1:-}" == "--force" ]] && shift
    rm -f "${root}/containers/$(safe "$1").labels"
    ;;
  *) exit 64 ;;
esac
DOCKER
chmod 0750 "${FAKE_DOCKER}"

SESSION_COMPOSE="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8dc1"
SESSION_TOMCAT="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8dc2"
RUN_ID="synthetic-run-5-2"

free_port() {
  python3 - <<'PY'
import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
PY
}

prepare_session() {
  local session="$1" project="$2" fixture="$3" port="$4"
  local root="${TEST_ROOT}/sessions/${session}"
  local worktree="${root}/${project}"
  local runtime="ws-${session//-/}"
  local runtime_root="${root}/runtime/${runtime}"
  local logs="${TEST_ROOT}/artifacts/sessions/${session}/runtime/logs"
  local artifacts="${TEST_ROOT}/artifacts/sessions/${session}/runs"
  local mirror="${TEST_ROOT}/repositories/${project}.git"
  mkdir -p "${worktree}" "${runtime_root}/engine-v1/source" \
    "${logs}" "${artifacts}/${RUN_ID}/browser" "${mirror}"
  cp -a "${REPO_ROOT}/runtime-contract/fixtures/valid/${fixture}/." "${worktree}/"
  printf '%s %s\n' "${session}" "${runtime}" >"${runtime_root}/engine-v1/.owner-v1"
  : >"${runtime_root}/engine-v1.lock"
  printf 'retained branch\n' >"${worktree}/uncommitted.txt"
  printf 'retained mirror\n' >"${mirror}/synthetic-ref"
  printf 'retained log\n' >"${logs}/runtime.log"
  printf 'retained artifact\n' >"${artifacts}/${RUN_ID}/result.txt"
  jq -n --arg session "${session}" --arg project "${project}" \
    --arg worktree "${worktree}" --arg mirror "${mirror}" '{
      schemaVersion: 1, sessionId: $session, projectId: $project,
      branch: ("atenea/session-" + $session), mirrorPath: $mirror,
      worktreePath: $worktree, state: "ready"
    }' >"${root}/workspace-v1.json"
  jq -n --arg session "${session}" --arg project "${project}" \
    --arg worktree "${worktree}" --arg mirror "${mirror}" \
    --arg runtime "${runtime}" --arg runtimeRoot "${runtime_root}" \
    --arg logs "${logs}" --arg artifacts "${artifacts}" --argjson port "${port}" '{
      schemaVersion: 1, sessionId: $session, projectId: $project,
      branch: ("atenea/session-" + $session), mirrorPath: $mirror,
      worktreePath: $worktree, runtimeId: $runtime, manifestRelativePath: "runtime.json",
      slot: "slot2", workloadClass: "normal", state: "allocated",
      runtimeNames: {
        composeProject: ($runtime + "-compose"), network: ($runtime + "-network"),
        volumePrefix: ($runtime + "-volume"), processUnit: ("atenea-" + $runtime + ".service"),
        tomcatBase: ($runtimeRoot + "/tomcat")
      },
      runtimeRoot: $runtimeRoot, logsPath: $logs, artifactsRoot: $artifacts,
      cacheRoot: ("/tmp/synthetic-cache/" + $session),
      allocatedPorts: [{name:"web", internalPort:8080, protocol:"http",
        bindAddress:"127.0.0.1", loopbackPort:$port}]
    }' >"${root}/runtime-allocation-v1.json"
  chmod 0640 "${root}/workspace-v1.json" "${root}/runtime-allocation-v1.json"
  local kind container image network labels
  kind="$(jq -r '.runtime.kind' "${worktree}/runtime.json")"
  container="${runtime}-$([[ "${kind}" == compose ]] && printf app || printf tomcat)"
  image="${runtime}-fixture-${kind}:task-4.3"
  network="${runtime}-network"
  labels="atenea-runtime-engine-v1 ${session} ${runtime}"
  printf '%s\n' "${labels}" >"${FAKE_STATE}/containers/${container}.labels"
  printf '%s\n' "${labels}" >"${FAKE_STATE}/networks/${network}.labels"
  printf '%s\n' "${labels}" >"${FAKE_STATE}/images/${image//:/_}.labels"
}

PORT_COMPOSE="$(free_port)"
PORT_TOMCAT="$(free_port)"
prepare_session "${SESSION_COMPOSE}" dummy-compose dummy-compose "${PORT_COMPOSE}"
prepare_session "${SESSION_TOMCAT}" dummy-tomcat dummy-tomcat "${PORT_TOMCAT}"

python3 -c '
import http.server, sys
payload = sys.argv[2].encode()
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.end_headers()
        self.wfile.write(payload)
    def log_message(self, *_):
        pass
http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
' "${PORT_COMPOSE}" '{"fixture":"dummy-compose","status":"UP"}' >/dev/null 2>&1 &
PIDS+=("$!")
python3 -c '
import http.server, sys
payload = sys.argv[2].encode()
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.end_headers()
        self.wfile.write(payload)
    def log_message(self, *_):
        pass
http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
' "${PORT_TOMCAT}" \
  '{"fixture":"dummy-tomcat","status":"UP","runtime":"java8-tomcat8"}' \
  >/dev/null 2>&1 &
PIDS+=("$!")
for url in \
  "http://127.0.0.1:${PORT_COMPOSE}/health" \
  "http://127.0.0.1:${PORT_TOMCAT}/"; do
  for unused in {1..40}; do
    curl -fsS --max-time 2 "${url}" >/dev/null 2>&1 && break
    sleep 0.05
  done
  curl -fsS --max-time 2 "${url}" | grep -q '"status":"UP"' ||
    fail "synthetic fixture health failed at ${url}"
done

CASES="${TEST_ROOT}/browser-cases.json"
jq -n \
  --arg cs "${SESSION_COMPOSE}" --arg ts "${SESSION_TOMCAT}" \
  --arg run "${RUN_ID}" --argjson cp "${PORT_COMPOSE}" --argjson tp "${PORT_TOMCAT}" '[
    {name:"compose-desktop",sessionId:$cs,runId:$run,url:("http://127.0.0.1:"+($cp|tostring)+"/health"),loopbackPort:$cp,route:"/health",width:1440,height:900,expectedText:["dummy-compose","UP"]},
    {name:"compose-mobile",sessionId:$cs,runId:$run,url:("http://127.0.0.1:"+($cp|tostring)+"/health"),loopbackPort:$cp,route:"/health",width:390,height:844,expectedText:["dummy-compose","UP"]},
    {name:"tomcat-desktop",sessionId:$ts,runId:$run,url:("http://127.0.0.1:"+($tp|tostring)+"/"),loopbackPort:$tp,route:"/",width:1440,height:900,expectedText:["dummy-tomcat","UP","java8-tomcat8"]},
    {name:"tomcat-mobile",sessionId:$ts,runId:$run,url:("http://127.0.0.1:"+($tp|tostring)+"/"),loopbackPort:$tp,route:"/",width:390,height:844,expectedText:["dummy-tomcat","UP","java8-tomcat8"]}
  ]' >"${CASES}"

rm -rf -- "${VISUAL_ROOT:?}/${SESSION_COMPOSE}" "${VISUAL_ROOT:?}/${SESSION_TOMCAT}"
rm -f -- "${VISUAL_ROOT}/browser-artifacts-v1.json"
DO_NOT_EXPOSE_BROWSER_CLEANUP_SECRET="${SECRET_MARKER}" \
  "${BROWSER_RUNNER}" "${BROWSER_CHECK}" "${CASES}" "${VISUAL_ROOT}" \
  >"${TEST_ROOT}/browser-first.stdout"
first_registry_hash="$(sha256sum "${VISUAL_ROOT}/browser-artifacts-v1.json" | cut -d' ' -f1)"
first_count="$(find "${VISUAL_ROOT}" -type f -name '*.png' | wc -l)"
[[ "${first_count}" -eq 4 ]] || fail "browser check did not retain exactly four screenshots"
DO_NOT_EXPOSE_BROWSER_CLEANUP_SECRET="${SECRET_MARKER}" \
  "${BROWSER_RUNNER}" "${BROWSER_CHECK}" "${CASES}" "${VISUAL_ROOT}" \
  >"${TEST_ROOT}/browser-second.stdout"
[[ "$(find "${VISUAL_ROOT}" -type f -name '*.png' | wc -l)" -eq 4 ]] ||
  fail "second browser run duplicated artifacts"
[[ "$(sha256sum "${VISUAL_ROOT}/browser-artifacts-v1.json" | cut -d' ' -f1)" == "${first_registry_hash}" ]] ||
  fail "second browser run changed deterministic artifact registration"

preserved_before="$(
  find "${TEST_ROOT}/sessions" "${TEST_ROOT}/repositories" "${TEST_ROOT}/artifacts" \
    -type f ! -path '*/runtime/*/engine-v1/*' ! -name 'engine-v1.lock' -print0 |
    sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1
)"

run_cleanup() {
  local session="$1"
  local root="${TEST_ROOT}/sessions/${session}"
  PATH="${TEST_ROOT}:${PATH}" \
  ATENEA_FAKE_DOCKER_STATE="${FAKE_STATE}" \
  DO_NOT_EXPOSE_BROWSER_CLEANUP_SECRET="${SECRET_MARKER}" \
    "${CLEANUP}" cleanup --session "${session}" \
      --allocation "${root}/runtime-allocation-v1.json" \
      --manifest "${root}/$(jq -r '.projectId' "${root}/runtime-allocation-v1.json")/runtime.json" \
      --docker-host "unix://${TEST_ROOT}/docker.sock" --json
}

run_cleanup "${SESSION_COMPOSE}" >"${TEST_ROOT}/cleanup-compose-first.json"
run_cleanup "${SESSION_TOMCAT}" >"${TEST_ROOT}/cleanup-tomcat-first.json"
run_cleanup "${SESSION_COMPOSE}" >"${TEST_ROOT}/cleanup-compose-second.json"
run_cleanup "${SESSION_TOMCAT}" >"${TEST_ROOT}/cleanup-tomcat-second.json"
jq -e '.removed == {container:false,network:false,image:false,temporary:false}' \
  "${TEST_ROOT}/cleanup-compose-second.json" "${TEST_ROOT}/cleanup-tomcat-second.json" >/dev/null ||
  fail "second cleanup was not idempotent"

preserved_after="$(
  find "${TEST_ROOT}/sessions" "${TEST_ROOT}/repositories" "${TEST_ROOT}/artifacts" \
    -type f ! -path '*/runtime/*/engine-v1/*' ! -name 'engine-v1.lock' -print0 |
    sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1
)"
[[ "${preserved_before}" == "${preserved_after}" ]] ||
  fail "cleanup changed records, worktrees, mirrors, logs or retained artifacts"

# Recreate targets with absent, foreign and partial labels. Validation must
# reject each one without deleting it.
foreign="${FAKE_STATE}/containers/ws-${SESSION_COMPOSE//-/}-app.labels"
for ownership_case in unlabelled foreign ambiguous; do
  case "${ownership_case}" in
    unlabelled) : >"${foreign}" ;;
    foreign)
      printf 'atenea-runtime-engine-v1 %s %s\n' \
        "${SESSION_TOMCAT}" "ws-${SESSION_TOMCAT//-/}" >"${foreign}"
      ;;
    ambiguous)
      printf 'atenea-runtime-engine-v1 %s \n' "${SESSION_COMPOSE}" >"${foreign}"
      ;;
  esac
  if run_cleanup "${SESSION_COMPOSE}" \
      >"${TEST_ROOT}/cleanup-${ownership_case}.stdout" \
      2>"${TEST_ROOT}/cleanup-${ownership_case}.stderr"; then
    fail "cleanup accepted ${ownership_case} ownership"
  fi
  grep -q '^RUNTIME_OWNERSHIP_CONFLICT:' \
    "${TEST_ROOT}/cleanup-${ownership_case}.stderr" ||
    fail "${ownership_case} cleanup rejection was not fixed and actionable"
  [[ -f "${foreign}" ]] ||
    fail "cleanup removed a resource with ${ownership_case} ownership"
  rm -f "${foreign}"
done

if grep -R -F "${SECRET_MARKER}" "${TEST_ROOT}" "${VISUAL_ROOT}/browser-artifacts-v1.json" >/dev/null 2>&1; then
  fail "results, logs, JSON or artifacts exposed an environment value"
fi
[[ -z "$(find "${FAKE_STATE}"/{containers,networks,images} -type f -print -quit)" ]] ||
  fail "synthetic Docker resources remain"

printf 'Project runtime health, browser, retention and cleanup v1 tests passed.\n'
