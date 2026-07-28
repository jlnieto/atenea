#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ADMISSION="${SCRIPT_DIR}/runtime-admission-v1.sh"
TEST_ROOT="$(mktemp -d /tmp/atenea-runtime-admission-test.XXXXXX)"

cleanup() {
  case "${TEST_ROOT}" in
    /tmp/atenea-runtime-admission-test.*)
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

CONTROL_ROOT="${TEST_ROOT}/control"
METRICS_FILE="${TEST_ROOT}/metrics.json"
PRESERVED_ROOT="${TEST_ROOT}/preserved"
mkdir -p \
  "${CONTROL_ROOT}" \
  "${PRESERVED_ROOT}/worktrees" \
  "${PRESERVED_ROOT}/logs" \
  "${PRESERVED_ROOT}/artifacts"
printf 'preserve worktree\n' >"${PRESERVED_ROOT}/worktrees/uncommitted.txt"
printf 'preserve logs\n' >"${PRESERVED_ROOT}/logs/runtime.log"
printf 'preserve artifacts\n' >"${PRESERVED_ROOT}/artifacts/result.txt"
preserved_hash="$(
  find "${PRESERVED_ROOT}" -type f -print0 |
    sort -z |
    xargs -0 sha256sum |
    sha256sum |
    cut -d' ' -f1
)"

write_metrics() {
  local load="$1" memory="$2" processes="$3"
  jq -cn \
    --argjson load "${load}" \
    --argjson memory "${memory}" \
    --argjson processes "${processes}" '{
      cpuCount: 16,
      loadMilli: $load,
      memoryAvailableBytes: $memory,
      processCount: $processes
    }' >"${METRICS_FILE}"
}
write_metrics 1000 42949672960 200

run_admission() {
  DO_NOT_EXPOSE_ADMISSION_SECRET="${DO_NOT_EXPOSE_ADMISSION_SECRET:-}" \
  ATENEA_RUNTIME_ADMISSION_TEST_MODE=1 \
  ATENEA_RUNTIME_ADMISSION_ROOT="${CONTROL_ROOT}" \
  ATENEA_RUNTIME_ADMISSION_METRICS_FILE="${METRICS_FILE}" \
    "${ADMISSION}" "$@"
}

expect_blocked_json() {
  local expected_code="$1"
  shift
  local output="${TEST_ROOT}/blocked.json"
  local diagnostic="${TEST_ROOT}/blocked.stderr"
  if run_admission --json "$@" >"${output}" 2>"${diagnostic}"; then
    fail "command unexpectedly succeeded: $*"
  fi
  jq -e \
    --arg code "${expected_code}" '
      .schemaVersion == 1 and .state == "blocked" and
      .error.code == $code and .error.retryable == true and
      (.error.message | type == "string" and length > 0) and
      (.error.action | type == "string" and length > 0) and
      .capacity.normalLimit == 4 and .capacity.heavyLimit == 2
    ' "${output}" >/dev/null ||
    fail "blocked JSON is not actionable: $(cat "${output}")"
  grep -q "^${expected_code}:" "${diagnostic}" ||
    fail "blocked request omitted its fixed stderr diagnostic"
}

expect_failure() {
  local expected_code="$1"
  shift
  local output
  if output="$(run_admission "$@" 2>&1)"; then
    fail "command unexpectedly succeeded: $*"
  fi
  grep -q "^${expected_code}:" <<<"${output}" ||
    fail "expected ${expected_code}, got: ${output}"
}

SESSION_ONE="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8d91"
SESSION_TWO="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8d92"
SESSION_THREE="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8d93"
SESSION_FOUR="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8d94"
SESSION_FIVE="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8d95"
SESSION_SIX="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8d96"

run_admission --json acquire-normal "${SESSION_ONE}" slot2 \
  >"${TEST_ROOT}/normal-${SESSION_ONE}.json"
pids=()
for session in "${SESSION_TWO}" "${SESSION_THREE}" "${SESSION_FOUR}"; do
  run_admission --json acquire-normal "${session}" \
    >"${TEST_ROOT}/normal-${session}.json" &
  pids+=("$!")
done
for pid in "${pids[@]}"; do
  wait "${pid}"
done

mapfile -t slots < <(
  jq -r '.record.normal.slot' "${TEST_ROOT}"/normal-*.json | sort
)
[[ "${slots[*]}" == "slot1 slot2 slot3 slot4" ]] ||
  fail "four concurrent WorkSessions did not receive four unique normal slots"
[[ "$(jq -r '.record.normal.slot' "${TEST_ROOT}/normal-${SESSION_ONE}.json")" == "slot2" ]] ||
  fail "requested proven-empty normal slot was not granted"
status="$(run_admission --json status)"
jq -e '
  .state == "ready" and
  .capacity.normalUsed == 4 and .capacity.normalLimit == 4 and
  .capacity.heavyUsed == 0 and .capacity.heavyLimit == 2
' <<<"${status}" >/dev/null ||
  fail "status did not expose four occupied normal slots"

record_one="${CONTROL_ROOT}/records/${SESSION_ONE}.json"
cp "${record_one}" "${TEST_ROOT}/record-one.before"
run_admission --json acquire-normal "${SESSION_ONE}" \
  >"${TEST_ROOT}/normal-repeat.json"
cmp -s "${record_one}" "${TEST_ROOT}/record-one.before" ||
  fail "repeated normal admission changed its persisted record"
run_admission --json acquire-normal "${SESSION_ONE}" slot2 \
  >"${TEST_ROOT}/normal-requested-repeat.json"
cmp -s "${record_one}" "${TEST_ROOT}/record-one.before" ||
  fail "repeated requested-slot admission changed its persisted record"
expect_failure RUNTIME_OWNERSHIP_CONFLICT \
  acquire-normal "${SESSION_ONE}" slot3
expect_blocked_json NORMAL_CAPACITY_EXHAUSTED \
  acquire-normal "${SESSION_FIVE}"
[[ ! -e "${CONTROL_ROOT}/records/${SESSION_FIVE}.json" ]] ||
  fail "blocked fifth normal request persisted an ownership record"

run_admission --json acquire-heavy "${SESSION_ONE}" \
  >"${TEST_ROOT}/heavy-one.json" &
heavy_one_pid=$!
run_admission --json acquire-heavy "${SESSION_TWO}" \
  >"${TEST_ROOT}/heavy-two.json" &
heavy_two_pid=$!
wait "${heavy_one_pid}"
wait "${heavy_two_pid}"
mapfile -t permits < <(
  jq -r '.record.heavy.permit' \
    "${TEST_ROOT}/heavy-one.json" "${TEST_ROOT}/heavy-two.json" | sort
)
[[ "${permits[*]}" == "heavy1 heavy2" ]] ||
  fail "two concurrent heavy operations did not receive independent permits"
expect_blocked_json HEAVY_CAPACITY_EXHAUSTED \
  acquire-heavy "${SESSION_THREE}"

run_admission --json release-heavy "${SESSION_ONE}" \
  >"${TEST_ROOT}/heavy-one-release.json"
run_admission --json acquire-heavy "${SESSION_THREE}" \
  >"${TEST_ROOT}/heavy-three.json"
jq -e '.record.heavy.state == "held"' "${TEST_ROOT}/heavy-three.json" >/dev/null ||
  fail "released heavy permit was not safely reused"
run_admission --json release-heavy "${SESSION_THREE}" >/dev/null

run_admission --json release-normal "${SESSION_FOUR}" \
  >"${TEST_ROOT}/normal-four-release.json"
run_admission --json acquire-normal "${SESSION_FIVE}" \
  >"${TEST_ROOT}/normal-five.json"
released_slot="$(jq -r '.record.normal.slot' "${TEST_ROOT}/normal-four-release.json")"
[[ "$(jq -r '.record.normal.slot' "${TEST_ROOT}/normal-five.json")" == "${released_slot}" ]] ||
  fail "released normal slot was not safely reused"

run_admission --json acquire-normal "${SESSION_FIVE}" \
  >"${TEST_ROOT}/normal-five-repeat-a.json" &
repeat_a=$!
run_admission --json acquire-normal "${SESSION_FIVE}" \
  >"${TEST_ROOT}/normal-five-repeat-b.json" &
repeat_b=$!
wait "${repeat_a}"
wait "${repeat_b}"
cmp -s \
  "${TEST_ROOT}/normal-five-repeat-a.json" \
  "${TEST_ROOT}/normal-five-repeat-b.json" ||
  fail "concurrent idempotent requests did not serialize to one result"

recovered_status="$(run_admission --json status)"
jq -e '
  .capacity.normalUsed == 4 and .capacity.heavyUsed == 1
' <<<"${recovered_status}" >/dev/null ||
  fail "a fresh helper process did not recover persisted slot and permit ownership"

run_admission --json release-heavy "${SESSION_TWO}" >/dev/null
run_admission --json release-normal "${SESSION_FIVE}" >/dev/null
write_metrics 1000 1073741824 200
expect_blocked_json NORMAL_CAPACITY_EXHAUSTED \
  acquire-normal "${SESSION_SIX}"
[[ ! -e "${CONTROL_ROOT}/records/${SESSION_SIX}.json" &&
    ! -e "${TEST_ROOT}/runtime-started" &&
    ! -e "${TEST_ROOT}/container-started" ]] ||
  fail "resource-pressure denial created runtime state"
write_metrics 13000 42949672960 200
expect_blocked_json HEAVY_CAPACITY_EXHAUSTED \
  acquire-heavy "${SESSION_ONE}"
write_metrics 1000 42949672960 9000
expect_blocked_json HEAVY_CAPACITY_EXHAUSTED \
  acquire-heavy "${SESSION_ONE}"
write_metrics 1000 42949672960 200
run_admission --json acquire-normal "${SESSION_FIVE}" >/dev/null

record_two="${CONTROL_ROOT}/records/${SESSION_TWO}.json"
record_three="${CONTROL_ROOT}/records/${SESSION_THREE}.json"
record_five="${CONTROL_ROOT}/records/${SESSION_FIVE}.json"
cp "${record_five}" "${TEST_ROOT}/record-five.backup"
duplicate_slot="$(jq -r '.normal.slot' "${record_one}")"
jq --arg slot "${duplicate_slot}" \
  '.normal = {slot: $slot, state: "held"}' \
  "${TEST_ROOT}/record-five.backup" >"${record_five}"
chmod 0640 "${record_five}"
expect_failure RUNTIME_OWNERSHIP_CONFLICT status
cp "${TEST_ROOT}/record-five.backup" "${record_five}"
chmod 0640 "${record_five}"

run_admission --json acquire-heavy "${SESSION_ONE}" >/dev/null
run_admission --json acquire-heavy "${SESSION_TWO}" >/dev/null
cp "${record_three}" "${TEST_ROOT}/record-three.backup"
duplicate_permit="$(jq -r '.heavy.permit' "${record_one}")"
jq --arg permit "${duplicate_permit}" \
  '.heavy = {permit: $permit, state: "held"}' \
  "${TEST_ROOT}/record-three.backup" >"${record_three}"
chmod 0640 "${record_three}"
expect_failure RUNTIME_OWNERSHIP_CONFLICT status
cp "${TEST_ROOT}/record-three.backup" "${record_three}"
chmod 0640 "${record_three}"

cp "${record_three}" "${TEST_ROOT}/record-three.identity"
jq --arg session "${SESSION_SIX}" '.sessionId = $session' \
  "${TEST_ROOT}/record-three.identity" >"${record_three}"
chmod 0640 "${record_three}"
expect_failure RUNTIME_OWNERSHIP_CONFLICT status
cp "${TEST_ROOT}/record-three.identity" "${record_three}"
chmod 0640 "${record_three}"

chmod 0666 "${record_two}"
expect_failure RUNTIME_OWNERSHIP_CONFLICT status
chmod 0640 "${record_two}"

human_status="$(run_admission status)"
grep -q '^Normal slots: 4/4$' <<<"${human_status}" ||
  fail "human status omitted normal capacity"
grep -q '^Heavy permits: 2/2$' <<<"${human_status}" ||
  fail "human status omitted heavy capacity"
grep -q '^Resource pressure: ready (ready)$' <<<"${human_status}" ||
  fail "human status omitted actionable pressure state"

SECRET_MARKER="DO_NOT_EXPOSE_ADMISSION_SECRET_64127"
export DO_NOT_EXPOSE_ADMISSION_SECRET="${SECRET_MARKER}"
run_admission --json status >"${TEST_ROOT}/secret-status.json"
run_admission --json acquire-normal "${SESSION_ONE}" \
  >"${TEST_ROOT}/secret-repeat.json"
if grep -R -F "${SECRET_MARKER}" \
    "${TEST_ROOT}/secret-status.json" "${TEST_ROOT}/secret-repeat.json" >/dev/null; then
  fail "admission output exposed an environment value"
fi

[[ "$(
  find "${PRESERVED_ROOT}" -type f -print0 |
    sort -z |
    xargs -0 sha256sum |
    sha256sum |
    cut -d' ' -f1
)" == "${preserved_hash}" ]] ||
  fail "admission changed preserved worktrees, logs or artifacts"
[[ "$(find "${CONTROL_ROOT}/records" -maxdepth 1 -type f -name '*.json' | wc -l)" -eq 5 ]] ||
  fail "release or conflict handling removed persisted admission records"

echo "Runtime admission v1 tests passed."
