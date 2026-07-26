#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
DEV="${SCRIPT_DIR}/dev-session-v1.sh"
ALLOCATOR="${SCRIPT_DIR}/session-runtime-allocation-v1.sh"
DEV_SCHEMA="${REPO_ROOT}/runtime-contract/dev-envelope-v1.schema.json"
ALLOCATION_SCHEMA="${REPO_ROOT}/runtime-contract/session-allocation-v1.schema.json"
TEST_ROOT="$(mktemp -d /tmp/atenea-dev-session-test.XXXXXX)"

cleanup() {
  case "${TEST_ROOT}" in
    /tmp/atenea-dev-session-test.*)
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

validate_envelope() {
  local document="$1"
  jq -e -s '
    length == 1 and
    .[0].schemaVersion == 1 and
    (.[0].operation |
      . == "list" or . == "status" or . == "build" or . == "up" or
      . == "stop" or . == "restart" or . == "redeploy" or
      . == "logs" or . == "url" or . == "doctor") and
    (.[0].state |
      . == "pending" or . == "running" or . == "ready" or
      . == "stopped" or . == "blocked" or . == "error" or
      . == "reconciling") and
    (.[0].timestamp |
      test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")) and
    (if (.[0].state == "blocked" or .[0].state == "error") then
      (.[0].error.code | type == "string") and
      (.[0].error.message | type == "string" and length > 0) and
      (.[0].error.retryable | type == "boolean") and
      (.[0].error.action | type == "string" and length > 0)
    else
      (.[0] | has("error") | not)
    end)
  ' "${document}" >/dev/null ||
    fail "JSON output at ${document} is not one structurally valid v1 envelope: $(cat "${document}")"

  if python3 -c 'import jsonschema' >/dev/null 2>&1; then
    PYTHONWARNINGS="ignore::DeprecationWarning" \
      python3 - "${DEV_SCHEMA}" "${ALLOCATION_SCHEMA}" "${document}" <<'PY'
import json
import sys
from jsonschema import Draft202012Validator, FormatChecker, RefResolver

dev_schema_path, allocation_schema_path, document_path = sys.argv[1:]
with open(dev_schema_path, encoding="utf-8") as handle:
    dev_schema = json.load(handle)
with open(allocation_schema_path, encoding="utf-8") as handle:
    allocation_schema = json.load(handle)
with open(document_path, encoding="utf-8") as handle:
    document = json.load(handle)
resolver = RefResolver.from_schema(
    dev_schema,
    store={allocation_schema["$id"]: allocation_schema},
)
Draft202012Validator(
    dev_schema,
    resolver=resolver,
    format_checker=FormatChecker(),
).validate(document)
PY
  fi
}

run_json_success() {
  local output_file="$1"
  shift
  : >"${TEST_ROOT}/json-success.stderr"
  run_dev --json "$@" >"${output_file}" 2>"${TEST_ROOT}/json-success.stderr"
  [[ ! -s "${TEST_ROOT}/json-success.stderr" ]] ||
    fail "successful JSON command wrote diagnostics to stderr"
  validate_envelope "${output_file}"
}

run_json_failure() {
  local expected_code="$1" output_file="$2"
  shift 2
  : >"${TEST_ROOT}/json-failure.stderr"
  if run_dev --json "$@" >"${output_file}" 2>"${TEST_ROOT}/json-failure.stderr"; then
    fail "JSON command unexpectedly succeeded: $*"
  fi
  validate_envelope "${output_file}"
  [[ "$(jq -r '.error.code' "${output_file}")" == "${expected_code}" ]] ||
    fail "expected JSON ${expected_code}, got: $(cat "${output_file}")"
  grep -q "^${expected_code}:" "${TEST_ROOT}/json-failure.stderr" ||
    fail "JSON failure did not keep its diagnostic on stderr"
}

WORKSPACE_ROOT="${TEST_ROOT}/workspaces"
ARTIFACT_ROOT="${TEST_ROOT}/artifacts"
CACHE_ROOT="${TEST_ROOT}/caches"
ALLOCATION_CONTROL_ROOT="${TEST_ROOT}/allocation-control"
RUNTIME_CLIENT="${TEST_ROOT}/runtime-client-v1"
ADAPTER_LOG="${TEST_ROOT}/adapter.log"
MIRROR_ROOT="${TEST_ROOT}/repositories"
mkdir -p \
  "${WORKSPACE_ROOT}" "${ARTIFACT_ROOT}" "${CACHE_ROOT}" \
  "${ALLOCATION_CONTROL_ROOT}" "${MIRROR_ROOT}"

cat >"${RUNTIME_CLIENT}" <<'ADAPTER'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '%s\n' "$*" >>"${ATENEA_TEST_ADAPTER_LOG}"
operation="$1"
shift
json=false
for argument in "$@"; do
  [[ "${argument}" == "--json" ]] && json=true
done
if [[ "${json}" == "true" ]]; then
  if [[ "${ATENEA_TEST_ADAPTER_MODE:-ready}" == "fail" ]]; then
    printf 'raw adapter output %s\n' "${ATENEA_TEST_SECRET_MARKER:-missing}"
    printf 'raw adapter diagnostic %s\n' "${ATENEA_TEST_SECRET_MARKER:-missing}" >&2
    exit 70
  fi
  case "${operation}" in
    status|up|restart|redeploy|doctor)
      printf '{"state":"ready","healthState":"healthy"}\n'
      ;;
    stop)
      printf '{"state":"stopped","healthState":"stopped"}\n'
      ;;
    build|logs)
      printf '{"state":"ready","healthState":"unknown"}\n'
      ;;
    *)
      exit 64
      ;;
  esac
  exit 0
fi
case "${operation}" in
  status)
    echo "Runtime state: synthetic-ready"
    ;;
  doctor)
    echo "Synthetic runtime boundary: ready"
    ;;
  logs)
    echo "synthetic log line"
    ;;
  build|up|stop|restart|redeploy)
    printf 'Runtime operation accepted: %s\n' "${operation}"
    ;;
  *)
    exit 64
    ;;
esac
ADAPTER
chmod 0750 "${RUNTIME_CLIENT}"

SESSION_ONE="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8d9e"
SESSION_TWO="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8d9f"
PROJECT="dummy-compose"

prepare_session() {
  local session="$1"
  local slot="$2"
  local session_root="${WORKSPACE_ROOT}/sessions/${session}"
  local worktree="${session_root}/${PROJECT}"
  local mirror="${MIRROR_ROOT}/${PROJECT}.git"
  mkdir -p "${worktree}/ops" "${mirror}"
  printf 'preserve worktree\n' >"${worktree}/uncommitted.txt"
  printf 'preserve mirror\n' >"${mirror}/synthetic-ref"
  jq -n \
    --arg session "${session}" \
    --arg project "${PROJECT}" \
    --arg branch "atenea/session-${session}" \
    --arg mirror "${mirror}" \
    --arg worktree "${worktree}" '{
      schemaVersion: 1,
      sessionId: $session,
      projectId: $project,
      branch: $branch,
      mirrorPath: $mirror,
      worktreePath: $worktree,
      state: "ready"
    }' >"${session_root}/workspace-v1.json"
  chmod 0640 "${session_root}/workspace-v1.json"
  jq -n \
    --arg project "${PROJECT}" '{
      schemaVersion: 1,
      project: {id: $project},
      runtime: {
        internalPorts: [
          {name: "web", port: 8080, protocol: "http"}
        ]
      },
      lifecycle: {
        build: {argv: ["/bin/false"], timeoutSeconds: 1},
        start: {argv: ["/bin/false"], timeoutSeconds: 1},
        stop: {argv: ["/bin/false"], timeoutSeconds: 1},
        health: {argv: ["/bin/false"], timeoutSeconds: 1},
        logs: {argv: ["/bin/false"], timeoutSeconds: 1}
      },
      preview: {
        internalPort: "web",
        path: "/ready",
        publish: "private",
        command: {argv: ["/bin/false"], timeoutSeconds: 1}
      },
      workloadClass: "normal"
    }' >"${worktree}/ops/atenea-runtime.json"
  ATENEA_RUNTIME_TEST_MODE=1 \
  ATENEA_WORKSPACE_ROOT="${WORKSPACE_ROOT}" \
  ATENEA_ARTIFACT_ROOT="${ARTIFACT_ROOT}" \
  ATENEA_CACHE_ROOT="${CACHE_ROOT}" \
  ATENEA_RUNTIME_CONTROL_ROOT="${ALLOCATION_CONTROL_ROOT}" \
  ATENEA_RUNTIME_PORT_START=25000 \
  ATENEA_RUNTIME_PORT_END=25199 \
    "${ALLOCATOR}" ensure "${session}" "${slot}" \
      "${worktree}/ops/atenea-runtime.json" \
      >"${TEST_ROOT}/allocation-${session}.json"
}

run_dev() {
  ATENEA_DEV_TEST_MODE=1 \
  ATENEA_WORKSPACE_ROOT="${WORKSPACE_ROOT}" \
  ATENEA_RUNTIME_CLIENT="${RUNTIME_CLIENT}" \
  ATENEA_TEST_ADAPTER_LOG="${ADAPTER_LOG}" \
  ATENEA_TEST_ADAPTER_MODE="${ATENEA_TEST_ADAPTER_MODE:-ready}" \
  ATENEA_TEST_SECRET_MARKER="${ATENEA_TEST_SECRET_MARKER:-}" \
    "${DEV}" "$@"
}

run_dev_without_client() {
  ATENEA_DEV_TEST_MODE=1 \
  ATENEA_WORKSPACE_ROOT="${WORKSPACE_ROOT}" \
  ATENEA_RUNTIME_CLIENT="${TEST_ROOT}/missing-runtime-client" \
    "${DEV}" "$@"
}

prepare_session "${SESSION_ONE}" slot1
prepare_session "${SESSION_TWO}" slot2

worktree_one="${WORKSPACE_ROOT}/sessions/${SESSION_ONE}/${PROJECT}"
record_one="${WORKSPACE_ROOT}/sessions/${SESSION_ONE}/runtime-allocation-v1.json"
record_two="${WORKSPACE_ROOT}/sessions/${SESSION_TWO}/runtime-allocation-v1.json"
record_one_hash="$(sha256sum "${record_one}" | cut -d' ' -f1)"
record_two_hash="$(sha256sum "${record_two}" | cut -d' ' -f1)"
worktree_hash="$(sha256sum "${worktree_one}/uncommitted.txt" | cut -d' ' -f1)"
mirror_hash="$(sha256sum "${MIRROR_ROOT}/${PROJECT}.git/synthetic-ref" | cut -d' ' -f1)"
logs_one="$(jq -r '.logsPath' "${record_one}")"
artifacts_one="$(jq -r '.artifactsRoot' "${record_one}")"
printf 'preserve dev log\n' >"${logs_one}/runtime.log"
printf 'preserve dev artifact\n' >"${artifacts_one}/result.txt"
log_hash="$(sha256sum "${logs_one}/runtime.log" | cut -d' ' -f1)"
artifact_hash="$(sha256sum "${artifacts_one}/result.txt" | cut -d' ' -f1)"

list_output="$(run_dev list)"
grep -q $'PROJECT\tSESSION\tSLOT\tSTATE' <<<"${list_output}" ||
  fail "dev list did not render its human header"
grep -q "${SESSION_ONE}" <<<"${list_output}" ||
  fail "dev list omitted the first WorkSession"
grep -q "${SESSION_TWO}" <<<"${list_output}" ||
  fail "dev list omitted the second WorkSession"

global_status="$(run_dev status)"
grep -q "${SESSION_ONE}" <<<"${global_status}" ||
  fail "global status did not list allocations"

session_status="$(run_dev --session "${SESSION_ONE}" status "${PROJECT}")"
grep -q "WorkSession: ${SESSION_ONE}" <<<"${session_status}" ||
  fail "explicit status did not select the requested WorkSession"
grep -q "Runtime state: synthetic-ready" <<<"${session_status}" ||
  fail "status did not delegate to the mediated runtime client"

cwd_status="$(
  cd "${worktree_one}"
  run_dev status
)"
grep -q "WorkSession: ${SESSION_ONE}" <<<"${cwd_status}" ||
  fail "current-worktree session resolution failed"
cwd_up="$(
  cd "${worktree_one}"
  run_dev up "${PROJECT}"
)"
grep -q "Runtime operation accepted: up" <<<"${cwd_up}" ||
  fail "mutating operation did not resolve the current WorkSession"

expected_port="$(
  jq -r '.allocatedPorts[] | select(.name == "web") | .loopbackPort' \
    "${record_one}"
)"
expected_url="http://127.0.0.1:${expected_port}/ready"
[[ "$(run_dev --session "${SESSION_ONE}" url)" == "${expected_url}" ]] ||
  fail "dev url did not use the persisted loopback allocation"

for operation in build up stop restart redeploy; do
  output="$(run_dev --session "${SESSION_ONE}" "${operation}" "${PROJECT}")"
  grep -q "Runtime operation accepted: ${operation}" <<<"${output}" ||
    fail "dev ${operation} did not delegate to the runtime client"
done
logs_output="$(run_dev --session "${SESSION_ONE}" --tail 37 logs)"
grep -q "synthetic log line" <<<"${logs_output}" ||
  fail "dev logs did not render mediated logs"
grep -q "^logs --session ${SESSION_ONE} .* --tail 37$" "${ADAPTER_LOG}" ||
  fail "dev logs did not pass the bounded tail value"

doctor_output="$(run_dev doctor)"
grep -q "Validated allocations: 2" <<<"${doctor_output}" ||
  fail "dev doctor did not validate every allocation"
grep -q "Runtime client: ready" <<<"${doctor_output}" ||
  fail "dev doctor did not report the mediated client"
session_doctor="$(run_dev --session "${SESSION_ONE}" doctor)"
grep -q "Synthetic runtime boundary: ready" <<<"${session_doctor}" ||
  fail "session doctor did not delegate through the runtime client"

expect_failure SESSION_REQUIRED run_dev up
expect_failure SESSION_AMBIGUOUS run_dev up "${PROJECT}"
expect_failure SESSION_IDENTITY_CONFLICT \
  run_dev --session "${SESSION_ONE}" status different-project
expect_failure OPERATION_FAILED run_dev --tail 10 status
expect_failure OPERATION_FAILED run_dev_without_client doctor
expect_failure OPERATION_FAILED \
  run_dev_without_client --session "${SESSION_ONE}" up
blocked_status="$(
  run_dev_without_client --session "${SESSION_ONE}" status
)"
grep -q "Runtime client: blocked (task 4.2 pending)" <<<"${blocked_status}" ||
  fail "status did not expose the pending mediated-runtime state"

SECRET_MARKER="DO_NOT_EXPOSE_DEV_JSON_SECRET_74291"
export ATENEA_TEST_SECRET_MARKER="${SECRET_MARKER}"

for operation in list status; do
  run_json_success "${TEST_ROOT}/json-${operation}.json" "${operation}"
  [[ "$(jq -r '.operation' "${TEST_ROOT}/json-${operation}.json")" == "${operation}" ]] ||
    fail "JSON ${operation} returned the wrong operation"
done
run_json_success \
  "${TEST_ROOT}/json-selected-status.json" \
  --session "${SESSION_ONE}" status
jq -e \
  --arg session "${SESSION_ONE}" \
  --arg project "${PROJECT}" '
    .sessionId == $session and .projectId == $project and
    .state == "ready" and .health.state == "healthy" and
    .allocation.sessionId == $session and
    (.url | startswith("http://127.0.0.1:"))
  ' "${TEST_ROOT}/json-selected-status.json" >/dev/null ||
  fail "selected JSON status omitted its identity, allocation, health or URL"

for operation in build up stop restart redeploy; do
  run_json_success \
    "${TEST_ROOT}/json-${operation}.json" \
    --session "${SESSION_ONE}" "${operation}" "${PROJECT}"
done
run_json_success \
  "${TEST_ROOT}/json-logs.json" \
  --session "${SESSION_ONE}" --tail 41 logs
grep -q "^logs --session ${SESSION_ONE} .* --tail 41 --json$" "${ADAPTER_LOG}" ||
  fail "JSON logs did not use the bounded synthetic adapter"
run_json_success \
  "${TEST_ROOT}/json-url.json" \
  --session "${SESSION_ONE}" url
[[ "$(jq -r '.url' "${TEST_ROOT}/json-url.json")" == "${expected_url}" ]] ||
  fail "JSON URL did not use the persisted allocation"
run_json_success "${TEST_ROOT}/json-doctor.json" doctor
run_json_success \
  "${TEST_ROOT}/json-session-doctor.json" \
  --session "${SESSION_ONE}" doctor

run_json_failure SESSION_REQUIRED \
  "${TEST_ROOT}/json-session-required.json" up
run_json_failure SESSION_AMBIGUOUS \
  "${TEST_ROOT}/json-session-ambiguous.json" up "${PROJECT}"
run_json_failure SESSION_IDENTITY_CONFLICT \
  "${TEST_ROOT}/json-identity-conflict.json" \
  --session "${SESSION_ONE}" status different-project

run_dev_without_client --json --session "${SESSION_ONE}" status \
  >"${TEST_ROOT}/json-runtime-pending.json" \
  2>"${TEST_ROOT}/json-runtime-pending.stderr" && \
  fail "pending runtime client unexpectedly succeeded"
validate_envelope "${TEST_ROOT}/json-runtime-pending.json"
jq -e '
  .state == "blocked" and
  .error.code == "OPERATION_FAILED" and
  (.error.action | contains("4.2"))
' "${TEST_ROOT}/json-runtime-pending.json" >/dev/null ||
  fail "pending runtime client was not an actionable blocked envelope"

export ATENEA_TEST_ADAPTER_MODE=fail
run_json_failure OPERATION_FAILED \
  "${TEST_ROOT}/json-operation-error.json" \
  --session "${SESSION_ONE}" up
jq -e '.state == "error"' "${TEST_ROOT}/json-operation-error.json" >/dev/null ||
  fail "adapter failure was not represented as an error state"
unset ATENEA_TEST_ADAPTER_MODE

if grep -F "${SECRET_MARKER}" "${TEST_ROOT}"/json-*.json >/dev/null; then
  fail "JSON envelope exposed an environment or raw adapter secret marker"
fi

manifest_one="${worktree_one}/ops/atenea-runtime.json"
mv "${manifest_one}" "${TEST_ROOT}/manifest.backup"
ln -s "${TEST_ROOT}/manifest.backup" "${manifest_one}"
expect_failure MANIFEST_INVALID run_dev list
run_json_failure MANIFEST_INVALID \
  "${TEST_ROOT}/json-manifest-invalid.json" list
rm "${manifest_one}"
mv "${TEST_ROOT}/manifest.backup" "${manifest_one}"

chmod 0666 "${record_two}"
expect_failure RUNTIME_OWNERSHIP_CONFLICT run_dev list
run_json_failure RUNTIME_OWNERSHIP_CONFLICT \
  "${TEST_ROOT}/json-ownership-conflict.json" list
chmod 0640 "${record_two}"

[[ "$(sha256sum "${record_one}" | cut -d' ' -f1)" == "${record_one_hash}" &&
    "$(sha256sum "${record_two}" | cut -d' ' -f1)" == "${record_two_hash}" ]] ||
  fail "dev modified an authoritative allocation record"
[[ "$(sha256sum "${worktree_one}/uncommitted.txt" | cut -d' ' -f1)" == "${worktree_hash}" ]] ||
  fail "dev modified uncommitted worktree state"
[[ "$(sha256sum "${MIRROR_ROOT}/${PROJECT}.git/synthetic-ref" | cut -d' ' -f1)" == "${mirror_hash}" ]] ||
  fail "dev modified the synthetic mirror"
[[ "$(sha256sum "${logs_one}/runtime.log" | cut -d' ' -f1)" == "${log_hash}" ]] ||
  fail "dev modified retained runtime logs"
[[ "$(sha256sum "${artifacts_one}/result.txt" | cut -d' ' -f1)" == "${artifact_hash}" ]] ||
  fail "dev modified retained run artifacts"
[[ ! -e "${TEST_ROOT}/lifecycle-command-ran" ]] ||
  fail "dev executed a manifest lifecycle command directly"

echo "Session dev v1 tests passed."
