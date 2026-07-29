#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
TEST_ROOT="$(mktemp -d /tmp/atenea-project-runtime-contract-test.XXXXXX)"
LISTENER_PIDS=()
SECRET_MARKER="DO_NOT_EXPOSE_GLOBAL_RUNTIME_SECRET_58247"

cleanup() {
  local pid
  for pid in "${LISTENER_PIDS[@]}"; do
    kill "${pid}" 2>/dev/null || true
    wait "${pid}" 2>/dev/null || true
  done
  case "${TEST_ROOT}" in
    /tmp/atenea-project-runtime-contract-test.*)
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

for command in curl find jq python3 sha256sum stat timeout; do
  command -v "${command}" >/dev/null ||
    fail "required test command is unavailable: ${command}"
done

declare -A PROTECTED_HASHES=(
  ["ops/worker/runtime-manager-v1.sh"]="e3ecfd2bb84dfd72d067dfe7bfa7426f062f4617a0800015e8f3e12dd2c7473f"
  ["ops/worker/runtime-client-v1.sh"]="0792bfae3f583474f51fef0d18169e4e7ffaad445efcc5f0de2470892b4089cb"
  ["ops/worker/test-runtime-manager-v1.sh"]="4e77070cbbbd5ca913297d7beb035d85af34bda22fcb64e64e0955fa8049a9cc"
  ["ops/worker/runtime-engine-v1.sh"]="48bc54324bf39086401fc7430a1b9b8048bcb6bd37e028bf8cad80e92bc4360e"
  ["ops/worker/atenea-runtime-engine-adapter-v1.sh"]="7e2a7b3007acd43e31ae1499bfcdafc3f5a0addce7bdba471bdcaf86b4e82600"
  ["ops/worker/install-atenea-runtime-v1.sh"]="2dcfbbe58eedd2b0dbd6f046416d90c9b8df52a5c6e415367d3e93fa481b296c"
  ["ops/worker/images/atenea-codex-app-server/Dockerfile"]="d6a5688825f46533074d800cd11b29a1656413cdf68249a07a4924b17829d27e"
  ["ops/worker/images/atenea-codex-app-server/codex-loopback-proxy.mjs"]="b62771d89fe1a26ca804f34c8712c3156b41134d235825c03a90a90daa7de64f"
  ["ops/worker/test-runtime-engine-v1.sh"]="305df286d09e036b64e88b4367a8cc290f66c71c14e63fdadd81c59adb41edd2"
  ["ops/worker/runtime-admission-v1.sh"]="a81366d3495bb2a7bf4702e9ea934a74e9b3edb30f728926e655a5c0a6a9f7ce"
  ["ops/worker/test-runtime-admission-v1.sh"]="aaa1b37d2dfc9d5eefecd7f9128b724bd8db69e138a719b977acec2d21bcaa86"
  ["runtime-contract/fixtures/valid/dummy-compose/runtime.json"]="db26ac0eb81d38c23c7883f2dda2c95c7dcbd3e4a9ee3509438293096938c5cb"
  ["runtime-contract/fixtures/valid/dummy-tomcat/runtime.json"]="f36c7a10e65cd148f4bbc0aa29fa6efdd5e097a0d9d573967cea20cf469f9d6a"
  ["runtime-contract/project-runtime-v1.schema.json"]="b5292048a54f3cb4952dd7f7a0dab15e4dddb71fcc7dbe05e228c11a2ffd8652"
  ["runtime-contract/fixtures/valid/database-postgresql/runtime.json"]="c1d55666315020eb439cffdf35d352ac343d7dfc75ea80ea842660dedc8db603"
  ["runtime-contract/fixtures/valid/database-mariadb/runtime.json"]="cbd2264a7900871cb4969cb6d5587863f6c4df4278ca2c408d886027cddec8a1"
  ["ops/worker/database-lifecycle-state-v1.py"]="209d4ade62737b47d2e0b1fd790c969111c0a924acfa0c386cd217eae0ce1c66"
  ["ops/worker/test-database-lifecycle-state-v1.py"]="ea3ba6489289757e3844ac0b8fdf89b36c7b22d2567bae03ebbdfdee6f39be6d"
)

assert_protected_hashes() {
  local relative expected actual
  for relative in "${!PROTECTED_HASHES[@]}"; do
    expected="${PROTECTED_HASHES[${relative}]}"
    actual="$(sha256sum "${REPO_ROOT}/${relative}" | cut -d' ' -f1)"
    [[ "${actual}" == "${expected}" ]] ||
      fail "protected hash changed for ${relative}: ${actual}"
  done
}

run_case() {
  local name="$1"
  shift
  local stdout="${TEST_ROOT}/${name}.stdout"
  local stderr="${TEST_ROOT}/${name}.stderr"
  local case_status=0
  if declare -F "$1" >/dev/null 2>&1; then
    DO_NOT_EXPOSE_GLOBAL_RUNTIME_SECRET="${SECRET_MARKER}" \
      "$@" >"${stdout}" 2>"${stderr}" || case_status=$?
  else
    DO_NOT_EXPOSE_GLOBAL_RUNTIME_SECRET="${SECRET_MARKER}" \
      timeout --foreground 300 "$@" >"${stdout}" 2>"${stderr}" ||
      case_status=$?
  fi
  if [[ "${case_status}" -ne 0 ]]; then
    printf 'Case %s stdout:\n' "${name}" >&2
    sed -n '1,160p' "${stdout}" >&2
    printf 'Case %s stderr:\n' "${name}" >&2
    sed -n '1,160p' "${stderr}" >&2
    fail "${name} failed"
  fi
  if grep -R -F "${SECRET_MARKER}" "${stdout}" "${stderr}" >/dev/null; then
    fail "${name} exposed an environment value"
  fi
  printf '[PASS] %s\n' "${name}"
}

validate_schema_corpus() {
  local schema="${REPO_ROOT}/runtime-contract/project-runtime-v1.schema.json"
  if python3 -c 'import jsonschema' >/dev/null 2>&1; then
    PYTHONWARNINGS="ignore::DeprecationWarning" \
      python3 - "${schema}" \
        "${REPO_ROOT}/runtime-contract/fixtures/valid/dummy-compose/runtime.json" \
        "${REPO_ROOT}/runtime-contract/fixtures/valid/dummy-tomcat/runtime.json" \
        "${REPO_ROOT}/runtime-contract/fixtures/valid/database-postgresql/runtime.json" \
        "${REPO_ROOT}/runtime-contract/fixtures/valid/database-mariadb/runtime.json" \
        "${REPO_ROOT}/runtime-contract/fixtures/invalid" <<'PY'
import json
import pathlib
import sys

from jsonschema import Draft202012Validator, FormatChecker

schema_path, compose_path, tomcat_path, postgresql_path, mariadb_path, invalid_root = sys.argv[1:]
with open(schema_path, encoding="utf-8") as handle:
    schema = json.load(handle)
Draft202012Validator.check_schema(schema)
validator = Draft202012Validator(schema, format_checker=FormatChecker())
for valid_path in (compose_path, tomcat_path, postgresql_path, mariadb_path):
    with open(valid_path, encoding="utf-8") as handle:
        validator.validate(json.load(handle))
for invalid_path in sorted(pathlib.Path(invalid_root).glob("*.json")):
    with invalid_path.open(encoding="utf-8") as handle:
        document = json.load(handle)
    if not list(validator.iter_errors(document)):
        raise SystemExit(f"invalid fixture unexpectedly passed: {invalid_path.name}")
PY
    return
  fi

  # AX42 intentionally has no host-global jsonschema package. Keep its
  # dependency-free check explicit and pair it with the manager denial suite.
  jq -e '
    . as $manifest |
    .schemaVersion == 1 and
    (.project.id == "dummy-compose" or .project.id == "dummy-tomcat") and
    (.runtime.internalPorts == [
      {name: "web", port: 8080, protocol: "http"}
    ]) and .secrets == [] and .workloadClass == "normal"
  ' \
    "${REPO_ROOT}/runtime-contract/fixtures/valid/dummy-compose/runtime.json" \
    "${REPO_ROOT}/runtime-contract/fixtures/valid/dummy-tomcat/runtime.json" \
    >/dev/null
  jq -e '
    .schemaVersion == 1 and
    .database.schemaVersion == 1 and
    .database.classification == "synthetic-development" and
    .database.syntheticDevelopmentFixture == true and
    .database.replacementMode == "explicit-confirmed" and
    .database.retention == {maxCopies: 3, maxAgeDays: 7} and
    (.database.image | test("@sha256:[a-f0-9]{64}$")) and
    ([.secrets[] | select(
      .name == $manifest.database.secretRef and
      .exposure == "database" and
      .required == true
    )] | length == 1)
  ' \
    "${REPO_ROOT}/runtime-contract/fixtures/valid/database-postgresql/runtime.json" \
    "${REPO_ROOT}/runtime-contract/fixtures/valid/database-mariadb/runtime.json" \
    >/dev/null
  jq -e '.runtime.composeFiles[0] | startswith("/")' \
    "${REPO_ROOT}/runtime-contract/fixtures/invalid/absolute-path.json" >/dev/null
  jq -e '.runtime | has("mounts")' \
    "${REPO_ROOT}/runtime-contract/fixtures/invalid/daemon-socket-mount.json" >/dev/null
  jq -e '.runtime | has("networkMode")' \
    "${REPO_ROOT}/runtime-contract/fixtures/invalid/host-network.json" >/dev/null
  jq -e 'any(.secrets[]; has("value"))' \
    "${REPO_ROOT}/runtime-contract/fixtures/invalid/literal-secret.json" >/dev/null
  jq -e 'has("lifecycle") | not' \
    "${REPO_ROOT}/runtime-contract/fixtures/invalid/missing-lifecycle.json" >/dev/null
  jq -e '.runtime.webappModule | contains("..")' \
    "${REPO_ROOT}/runtime-contract/fixtures/invalid/parent-traversal.json" >/dev/null
  jq -e '.runtime | has("privileged")' \
    "${REPO_ROOT}/runtime-contract/fixtures/invalid/privileged.json" >/dev/null
  jq -e '.schemaVersion != 1' \
    "${REPO_ROOT}/runtime-contract/fixtures/invalid/wrong-schema-version.json" >/dev/null
}

expect_denial() {
  local expected_code="$1" stdout="$2" stderr="$3"
  shift 3
  : >"${stdout}"
  : >"${stderr}"
  if DO_NOT_EXPOSE_GLOBAL_RUNTIME_SECRET="${SECRET_MARKER}" \
      "$@" >"${stdout}" 2>"${stderr}"; then
    fail "denied command unexpectedly succeeded: $*"
  fi
  grep -q "^${expected_code}:" "${stderr}" ||
    fail "expected ${expected_code}, got: $(cat "${stderr}")"
  if grep -F "${SECRET_MARKER}" "${stdout}" "${stderr}" >/dev/null; then
    fail "denied command exposed an environment value"
  fi
}

run_integrated_allocation_and_admission() {
  local admission="${SCRIPT_DIR}/runtime-admission-v1.sh"
  local allocator="${SCRIPT_DIR}/session-runtime-allocation-v1.sh"
  local control="${TEST_ROOT}/integrated/admission"
  local allocation_control="${TEST_ROOT}/integrated/allocation"
  local workspaces="${TEST_ROOT}/integrated/workspaces"
  local artifacts="${TEST_ROOT}/integrated/artifacts"
  local caches="${TEST_ROOT}/integrated/caches"
  local mirrors="${TEST_ROOT}/integrated/repositories"
  local metrics="${TEST_ROOT}/integrated/metrics.json"
  local preserved="${TEST_ROOT}/integrated/preserved"
  local -a sessions=(
    "018f47a2-6b0c-7a31-9c2d-4f5a6b7c8db1"
    "018f47a2-6b0c-7a31-9c2d-4f5a6b7c8db2"
    "018f47a2-6b0c-7a31-9c2d-4f5a6b7c8db3"
    "018f47a2-6b0c-7a31-9c2d-4f5a6b7c8db4"
  )
  local fifth="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8db5"
  local session project fixture session_root worktree mirror slot
  local pid index

  mkdir -p \
    "${control}" "${allocation_control}" "${workspaces}" "${artifacts}" \
    "${caches}" "${mirrors}" "${preserved}"
  jq -cn '{
    cpuCount: 16,
    loadMilli: 1000,
    memoryAvailableBytes: 42949672960,
    processCount: 200
  }' >"${metrics}"

  run_admission() {
    ATENEA_RUNTIME_ADMISSION_TEST_MODE=1 \
    ATENEA_RUNTIME_ADMISSION_ROOT="${control}" \
    ATENEA_RUNTIME_ADMISSION_METRICS_FILE="${metrics}" \
      "${admission}" "$@"
  }

  run_allocator() {
    ATENEA_RUNTIME_TEST_MODE=1 \
    ATENEA_WORKSPACE_ROOT="${workspaces}" \
    ATENEA_ARTIFACT_ROOT="${artifacts}" \
    ATENEA_CACHE_ROOT="${caches}" \
    ATENEA_RUNTIME_CONTROL_ROOT="${allocation_control}" \
    ATENEA_RUNTIME_PORT_START=28200 \
    ATENEA_RUNTIME_PORT_END=28399 \
      "${allocator}" "$@"
  }

  for session in "${sessions[@]}"; do
    run_admission --json acquire-normal "${session}" \
      >"${TEST_ROOT}/integrated/admission-${session}.json" &
  done
  wait

  mapfile -t assigned_slots < <(
    jq -r '.record.normal.slot' \
      "${TEST_ROOT}"/integrated/admission-*.json | sort
  )
  [[ "${assigned_slots[*]}" == "slot1 slot2 slot3 slot4" ]] ||
    fail "concurrent admission did not assign exactly four unique slots"

  for index in "${!sessions[@]}"; do
    session="${sessions[${index}]}"
    if ((index % 2 == 0)); then
      project="dummy-compose"
      fixture="dummy-compose"
    else
      project="dummy-tomcat"
      fixture="dummy-tomcat"
    fi
    session_root="${workspaces}/sessions/${session}"
    worktree="${session_root}/${project}"
    mirror="${mirrors}/${project}.git"
    mkdir -p "${worktree}" "${mirror}"
    cp -a "${REPO_ROOT}/runtime-contract/fixtures/valid/${fixture}/." "${worktree}/"
    printf 'preserve worktree %s\n' "${session}" >"${worktree}/uncommitted.txt"
    printf 'preserve mirror %s\n' "${session}" >"${mirror}/synthetic-ref-${session}"
    jq -n \
      --arg session "${session}" \
      --arg project "${project}" \
      --arg mirror "${mirror}" \
      --arg worktree "${worktree}" '{
        schemaVersion: 1,
        sessionId: $session,
        projectId: $project,
        branch: ("atenea/session-" + $session),
        mirrorPath: $mirror,
        worktreePath: $worktree,
        state: "ready"
      }' >"${session_root}/workspace-v1.json"
    chmod 0640 "${session_root}/workspace-v1.json"
    slot="$(jq -r '.record.normal.slot' \
      "${TEST_ROOT}/integrated/admission-${session}.json")"
    run_allocator ensure "${session}" "${slot}" "${worktree}/runtime.json" \
      >"${TEST_ROOT}/integrated/allocation-${session}.json" &
  done
  wait

  for session in "${sessions[@]}"; do
    slot="$(jq -r '.record.normal.slot' \
      "${TEST_ROOT}/integrated/admission-${session}.json")"
    jq -e --arg session "${session}" --arg slot "${slot}" '
      .sessionId == $session and .slot == $slot and
      .allocatedPorts[0].internalPort == 8080 and
      .allocatedPorts[0].bindAddress == "127.0.0.1"
    ' "${TEST_ROOT}/integrated/allocation-${session}.json" >/dev/null ||
      fail "allocation and admission disagree for ${session}"
  done
  [[ "$(
    jq -r '.allocatedPorts[0].loopbackPort' \
      "${TEST_ROOT}"/integrated/allocation-*.json | sort -u | wc -l
  )" -eq 4 ]] || fail "four internal port 8080 allocations are not isolated"

  session="${sessions[0]}"
  project="dummy-compose"
  worktree="${workspaces}/sessions/${session}/${project}"
  slot="$(jq -r '.record.normal.slot' \
    "${TEST_ROOT}/integrated/admission-${session}.json")"
  cp "${TEST_ROOT}/integrated/allocation-${session}.json" \
    "${TEST_ROOT}/integrated/allocation-repeat-before.json"
  run_allocator ensure "${session}" "${slot}" "${worktree}/runtime.json" \
    >"${TEST_ROOT}/integrated/allocation-repeat.json" &
  repeat_one=$!
  run_allocator ensure "${session}" "${slot}" "${worktree}/runtime.json" \
    >"${TEST_ROOT}/integrated/allocation-repeat-concurrent.json" &
  repeat_two=$!
  wait "${repeat_one}"
  wait "${repeat_two}"
  cmp -s \
    "${TEST_ROOT}/integrated/allocation-repeat-before.json" \
    "${TEST_ROOT}/integrated/allocation-repeat.json" &&
    cmp -s \
      "${TEST_ROOT}/integrated/allocation-repeat.json" \
      "${TEST_ROOT}/integrated/allocation-repeat-concurrent.json" ||
    fail "concurrent repeated allocation was not byte-stable"

  run_admission --json acquire-normal "${session}" \
    >"${TEST_ROOT}/integrated/admission-repeat-a.json" &
  repeat_one=$!
  run_admission --json acquire-normal "${session}" \
    >"${TEST_ROOT}/integrated/admission-repeat-b.json" &
  repeat_two=$!
  wait "${repeat_one}"
  wait "${repeat_two}"
  cmp -s \
    "${TEST_ROOT}/integrated/admission-repeat-a.json" \
    "${TEST_ROOT}/integrated/admission-repeat-b.json" ||
    fail "concurrent repeated admission was not byte-stable"

  expect_denial NORMAL_CAPACITY_EXHAUSTED \
    "${TEST_ROOT}/integrated/fifth.stdout" \
    "${TEST_ROOT}/integrated/fifth.stderr" \
    run_admission --json acquire-normal "${fifth}"
  jq -e '
    .state == "blocked" and
    .error.code == "NORMAL_CAPACITY_EXHAUSTED" and
    .capacity.normalUsed == 4
  ' "${TEST_ROOT}/integrated/fifth.stdout" >/dev/null ||
    fail "fifth-session rejection was not a stable structured envelope"
  [[ ! -e "${control}/records/${fifth}.json" &&
      ! -e "${workspaces}/sessions/${fifth}" ]] ||
    fail "fifth-session rejection created runtime or ownership state"

  run_admission --json acquire-heavy "${sessions[0]}" \
    >"${TEST_ROOT}/integrated/heavy-one.json" &
  heavy_one=$!
  run_admission --json acquire-heavy "${sessions[1]}" \
    >"${TEST_ROOT}/integrated/heavy-two.json" &
  heavy_two=$!
  wait "${heavy_one}"
  wait "${heavy_two}"
  expect_denial HEAVY_CAPACITY_EXHAUSTED \
    "${TEST_ROOT}/integrated/heavy-third.stdout" \
    "${TEST_ROOT}/integrated/heavy-third.stderr" \
    run_admission --json acquire-heavy "${sessions[2]}"
  jq -e '
    .state == "blocked" and
    .error.code == "HEAVY_CAPACITY_EXHAUSTED" and
    .capacity.heavyUsed == 2
  ' "${TEST_ROOT}/integrated/heavy-third.stdout" >/dev/null ||
    fail "third-heavy rejection was not a stable structured envelope"

  other_slot="$(jq -r '.record.normal.slot' \
    "${TEST_ROOT}/integrated/admission-${sessions[1]}.json")"
  expect_denial SESSION_IDENTITY_CONFLICT \
    "${TEST_ROOT}/integrated/cross-allocation.stdout" \
    "${TEST_ROOT}/integrated/cross-allocation.stderr" \
    run_allocator ensure "${sessions[0]}" "${other_slot}" \
      "${workspaces}/sessions/${sessions[0]}/dummy-compose/runtime.json"

  for index in 0 1; do
    session="${sessions[${index}]}"
    project="$([[ "${index}" -eq 0 ]] && printf dummy-compose || printf dummy-tomcat)"
    port="$(jq -r '.allocatedPorts[0].loopbackPort' \
      "${TEST_ROOT}/integrated/allocation-${session}.json")"
    serve_root="${TEST_ROOT}/integrated/serve-${session}"
    mkdir -p "${serve_root}"
    printf '%s\n' "${session}" >"${serve_root}/index.html"
    python3 -m http.server "${port}" --bind 127.0.0.1 \
      --directory "${serve_root}" \
      >"${TEST_ROOT}/integrated/http-${session}.log" 2>&1 &
    LISTENER_PIDS+=("$!")
  done
  for index in 0 1; do
    session="${sessions[${index}]}"
    port="$(jq -r '.allocatedPorts[0].loopbackPort' \
      "${TEST_ROOT}/integrated/allocation-${session}.json")"
    response=""
    for unused in {1..40}; do
      response="$(timeout 2 curl -fsS "http://127.0.0.1:${port}/" || true)"
      [[ "${response}" == "${session}" ]] && break
      sleep 0.05
    done
    [[ "${response}" == "${session}" ]] ||
      fail "same-port synthetic runtime did not preserve session identity"
  done

  printf 'preserve logs\n' \
    >"$(jq -r '.logsPath' "${TEST_ROOT}/integrated/allocation-${sessions[0]}.json")/runtime.log"
  printf 'preserve artifacts\n' \
    >"$(jq -r '.artifactsRoot' "${TEST_ROOT}/integrated/allocation-${sessions[0]}.json")/result.txt"
  find "${workspaces}" "${artifacts}" "${mirrors}" -type f -print0 |
    sort -z |
    xargs -0 sha256sum >"${preserved}/before.sha256"
  run_admission status >"${TEST_ROOT}/integrated/human-status.txt"
  [[ "$(wc -l <"${TEST_ROOT}/integrated/human-status.txt")" -eq 3 ]] ||
    fail "human admission state is not concise"
  run_allocator ensure "${sessions[0]}" "${slot}" \
    "${workspaces}/sessions/${sessions[0]}/dummy-compose/runtime.json" >/dev/null
  find "${workspaces}" "${artifacts}" "${mirrors}" -type f -print0 |
    sort -z |
    xargs -0 sha256sum >"${preserved}/after.sha256"
  cmp -s "${preserved}/before.sha256" "${preserved}/after.sha256" ||
    fail "idempotent and denied operations changed retained evidence"

  [[ "$(find "${control}/records" -maxdepth 1 -type f -name '*.json' | wc -l)" -eq 4 &&
      "$(find "${workspaces}/sessions" -mindepth 2 -maxdepth 2 \
        -name runtime-allocation-v1.json -type f | wc -l)" -eq 4 ]] ||
    fail "idempotent repetition duplicated or removed persisted records"
}

assert_protected_hashes
run_case schema-corpus validate_schema_corpus
run_case workspace-boundary "${SCRIPT_DIR}/test-session-workspace-v1.sh"
run_case allocation-regression "${SCRIPT_DIR}/test-session-runtime-allocation-v1.sh"
run_case dev-regression "${SCRIPT_DIR}/test-dev-session-v1.sh"
run_case manager-regression "${SCRIPT_DIR}/test-runtime-manager-v1.sh"
run_case engine-regression "${SCRIPT_DIR}/test-runtime-engine-v1.sh"
run_case admission-regression "${SCRIPT_DIR}/test-runtime-admission-v1.sh"
run_case integrated-capacity run_integrated_allocation_and_admission
assert_protected_hashes

if find /tmp -maxdepth 1 -type d \
    \( -name 'atenea-workspace-test.*' \
       -o -name 'atenea-runtime-allocation-test.*' \
       -o -name 'atenea-dev-session-test.*' \
       -o -name 'atenea-runtime-manager-test.*' \
       -o -name 'atenea-runtime-engine-test.*' \
       -o -name 'atenea-runtime-admission-test.*' \) \
    -print -quit | grep -q .; then
  fail "a regression suite left a temporary fixture behind"
fi

printf 'Project runtime contract v1 integration tests passed (8/8).\n'
