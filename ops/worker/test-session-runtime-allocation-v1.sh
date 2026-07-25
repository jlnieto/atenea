#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
HELPER="${SCRIPT_DIR}/session-runtime-allocation-v1.sh"
TEST_ROOT="$(mktemp -d /tmp/atenea-runtime-allocation-test.XXXXXX)"
LISTENER_PID=""

cleanup() {
  if [[ -n "${LISTENER_PID}" ]]; then
    kill "${LISTENER_PID}" 2>/dev/null || true
    wait "${LISTENER_PID}" 2>/dev/null || true
  fi
  case "${TEST_ROOT}" in
    /tmp/atenea-runtime-allocation-test.*)
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
CONTROL_ROOT="${TEST_ROOT}/control"
MIRROR_ROOT="${TEST_ROOT}/repositories"
mkdir -p \
  "${WORKSPACE_ROOT}" "${ARTIFACT_ROOT}" "${CACHE_ROOT}" \
  "${CONTROL_ROOT}" "${MIRROR_ROOT}"

SESSION_ONE="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8d9e"
SESSION_TWO="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8d9f"
SESSION_THREE="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8da0"
SESSION_FOUR="018f47a2-6b0c-7a31-9c2d-4f5a6b7c8da1"
PROJECT="dummy-compose"

prepare_session() {
  local session="$1"
  local session_root="${WORKSPACE_ROOT}/sessions/${session}"
  local worktree="${session_root}/${PROJECT}"
  local mirror="${MIRROR_ROOT}/${PROJECT}.git"
  mkdir -p "${worktree}" "${mirror}"
  printf 'preserve mirror\n' >"${mirror}/synthetic-ref"
  printf 'preserve worktree\n' >"${worktree}/uncommitted.txt"
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
          {name: "web", port: 8080, protocol: "http"},
          {name: "debug", port: 5005, protocol: "tcp"}
        ]
      },
      workloadClass: "normal"
    }' >"${worktree}/runtime.json"
}

run_helper() {
  ATENEA_RUNTIME_TEST_MODE=1 \
  ATENEA_WORKSPACE_ROOT="${WORKSPACE_ROOT}" \
  ATENEA_ARTIFACT_ROOT="${ARTIFACT_ROOT}" \
  ATENEA_CACHE_ROOT="${CACHE_ROOT}" \
  ATENEA_RUNTIME_CONTROL_ROOT="${CONTROL_ROOT}" \
  ATENEA_RUNTIME_PORT_START=24000 \
  ATENEA_RUNTIME_PORT_END=24199 \
    "${HELPER}" "$@"
}

for session in \
  "${SESSION_ONE}" "${SESSION_TWO}" "${SESSION_THREE}" "${SESSION_FOUR}"; do
  prepare_session "${session}"
done

manifest_one="${WORKSPACE_ROOT}/sessions/${SESSION_ONE}/${PROJECT}/runtime.json"
manifest_two="${WORKSPACE_ROOT}/sessions/${SESSION_TWO}/${PROJECT}/runtime.json"
run_helper ensure "${SESSION_ONE}" slot1 "${manifest_one}" >"${TEST_ROOT}/one.json"
run_helper ensure "${SESSION_TWO}" slot2 "${manifest_two}" >"${TEST_ROOT}/two.json"

runtime_one="ws-${SESSION_ONE//-/}"
runtime_two="ws-${SESSION_TWO//-/}"
port_one="$(jq -r '.allocatedPorts[] | select(.name == "web") | .loopbackPort' "${TEST_ROOT}/one.json")"
port_two="$(jq -r '.allocatedPorts[] | select(.name == "web") | .loopbackPort' "${TEST_ROOT}/two.json")"
[[ "${runtime_one}" != "${runtime_two}" ]] ||
  fail "full WorkSession UUIDs did not produce distinct runtime identities"
[[ "$(jq -r '.runtimeId' "${TEST_ROOT}/one.json")" == "${runtime_one}" ]] ||
  fail "runtime identity did not use the complete WorkSession UUID"
[[ "${port_one}" != "${port_two}" ]] ||
  fail "sessions declaring the same internal port received one loopback port"
for field in composeProject network volumePrefix processUnit tomcatBase; do
  one_name="$(jq -r --arg field "${field}" '.runtimeNames[$field]' "${TEST_ROOT}/one.json")"
  two_name="$(jq -r --arg field "${field}" '.runtimeNames[$field]' "${TEST_ROOT}/two.json")"
  [[ "${one_name}" == *"${runtime_one}"* && "${one_name}" != "${two_name}" ]] ||
    fail "runtime name ${field} is not complete-session-derived and unique"
done
jq -e '
  all(.allocatedPorts[];
    .bindAddress == "127.0.0.1" and
    (.loopbackPort >= 24000 and .loopbackPort <= 24199))
' "${TEST_ROOT}/one.json" >/dev/null ||
  fail "allocated ports are not bounded loopback allocations"

logs_one="$(jq -r '.logsPath' "${TEST_ROOT}/one.json")"
artifacts_one="$(jq -r '.artifactsRoot' "${TEST_ROOT}/one.json")"
cache_one="$(jq -r '.cacheRoot' "${TEST_ROOT}/one.json")"
printf 'preserve log\n' >"${logs_one}/runtime.log"
printf 'preserve artifact\n' >"${artifacts_one}/result.txt"
printf 'preserve cache\n' >"${cache_one}/maven/cache-entry"
mirror_hash="$(sha256sum "${MIRROR_ROOT}/${PROJECT}.git/synthetic-ref" | cut -d' ' -f1)"
worktree_hash="$(
  sha256sum \
    "${WORKSPACE_ROOT}/sessions/${SESSION_ONE}/${PROJECT}/uncommitted.txt" |
    cut -d' ' -f1
)"
run_helper ensure "${SESSION_ONE}" slot1 "${manifest_one}" >"${TEST_ROOT}/one-repeat.json"
cmp -s "${TEST_ROOT}/one.json" "${TEST_ROOT}/one-repeat.json" ||
  fail "repeated allocation was not byte-stable"
[[ "$(cat "${logs_one}/runtime.log")" == "preserve log" &&
    "$(cat "${artifacts_one}/result.txt")" == "preserve artifact" &&
    "$(cat "${cache_one}/maven/cache-entry")" == "preserve cache" ]] ||
  fail "idempotent allocation modified retained roots"
[[ "$(sha256sum "${MIRROR_ROOT}/${PROJECT}.git/synthetic-ref" | cut -d' ' -f1)" == "${mirror_hash}" ]] ||
  fail "runtime allocation modified the synthetic mirror"
[[ "$(
  sha256sum \
    "${WORKSPACE_ROOT}/sessions/${SESSION_ONE}/${PROJECT}/uncommitted.txt" |
    cut -d' ' -f1
)" == "${worktree_hash}" ]] ||
  fail "runtime allocation modified the synthetic worktree"

cache_policy="${cache_one}/cache-policy-v1.json"
jq -e '
  .authoritative == false and .rebuildable == true and
  .secretsAllowed == false and
  .scopes == ["browser", "maven", "node", "oci"]
' "${cache_policy}" >/dev/null ||
  fail "cache policy does not prohibit secrets or declare rebuildable scope"
if find "${cache_one}" -type f ! -name cache-policy-v1.json ! -name cache-entry \
  -print -quit | grep -q .; then
  fail "allocator placed unexpected state in the cache root"
fi
[[ ! -e "${cache_one}/workspace-v1.json" &&
    ! -e "${cache_one}/runtime-allocation-v1.json" ]] ||
  fail "authoritative state was stored beneath the cache root"

rmdir "${cache_one}/browser"
mkdir "${TEST_ROOT}/outside-cache"
ln -s "${TEST_ROOT}/outside-cache" "${cache_one}/browser"
expect_failure RUNTIME_OWNERSHIP_CONFLICT \
  run_helper ensure "${SESSION_ONE}" slot1 "${manifest_one}"
[[ ! -e "${TEST_ROOT}/outside-cache/cache-policy-v1.json" &&
    "$(cat "${logs_one}/runtime.log")" == "preserve log" &&
    "$(cat "${artifacts_one}/result.txt")" == "preserve artifact" ]] ||
  fail "symbolic-link rejection modified external or retained state"
rm "${cache_one}/browser"
mkdir "${cache_one}/browser"

record_one="${WORKSPACE_ROOT}/sessions/${SESSION_ONE}/runtime-allocation-v1.json"
cp "${record_one}" "${TEST_ROOT}/record-one.backup"
jq '.projectId = "different-project"' "${record_one}" >"${TEST_ROOT}/record.tmp"
chmod 0640 "${TEST_ROOT}/record.tmp"
mv "${TEST_ROOT}/record.tmp" "${record_one}"
expect_failure SESSION_IDENTITY_CONFLICT \
  run_helper ensure "${SESSION_ONE}" slot1 "${manifest_one}"
cp "${TEST_ROOT}/record-one.backup" "${record_one}"
chmod 0640 "${record_one}"

jq '.state = "ready"' "${record_one}" >"${TEST_ROOT}/record.tmp"
chmod 0640 "${TEST_ROOT}/record.tmp"
mv "${TEST_ROOT}/record.tmp" "${record_one}"
expect_failure RECONCILIATION_REQUIRED \
  run_helper ensure "${SESSION_ONE}" slot1 "${manifest_one}"
cp "${TEST_ROOT}/record-one.backup" "${record_one}"
chmod 0640 "${record_one}"

record_two="${WORKSPACE_ROOT}/sessions/${SESSION_TWO}/runtime-allocation-v1.json"
cp "${record_two}" "${TEST_ROOT}/record-two.backup"
jq --argjson port "${port_one}" \
  '(.allocatedPorts[] | select(.name == "web") | .loopbackPort) = $port' \
  "${record_two}" >"${TEST_ROOT}/record.tmp"
chmod 0640 "${TEST_ROOT}/record.tmp"
mv "${TEST_ROOT}/record.tmp" "${record_two}"
expect_failure RUNTIME_OWNERSHIP_CONFLICT \
  run_helper ensure "${SESSION_ONE}" slot1 "${manifest_one}"
cp "${TEST_ROOT}/record-two.backup" "${record_two}"
chmod 0640 "${record_two}"

chmod 0666 "${record_two}"
expect_failure RUNTIME_OWNERSHIP_CONFLICT \
  run_helper ensure "${SESSION_ONE}" slot1 "${manifest_one}"
chmod 0640 "${record_two}"

manifest_three="${WORKSPACE_ROOT}/sessions/${SESSION_THREE}/${PROJECT}/runtime.json"
manifest_four="${WORKSPACE_ROOT}/sessions/${SESSION_FOUR}/${PROJECT}/runtime.json"
hash_prefix="$(
  printf '%s' "${SESSION_THREE}:web" |
    sha256sum |
    cut -c1-8
)"
occupied_port="$((24000 + 16#${hash_prefix} % 200))"
python3 -m http.server "${occupied_port}" --bind 127.0.0.1 \
  >/dev/null 2>&1 &
LISTENER_PID=$!
for unused in {1..20}; do
  ss -H -ltn "sport = :${occupied_port}" | grep -q . && break
  sleep 0.05
done
ss -H -ltn "sport = :${occupied_port}" | grep -q . ||
  fail "synthetic loopback listener did not start"
run_helper ensure "${SESSION_THREE}" slot3 "${manifest_three}" \
  >"${TEST_ROOT}/three.json" &
first_pid=$!
run_helper ensure "${SESSION_FOUR}" slot4 "${manifest_four}" \
  >"${TEST_ROOT}/four.json" &
second_pid=$!
wait "${first_pid}"
wait "${second_pid}"
port_three="$(jq -r '.allocatedPorts[] | select(.name == "web") | .loopbackPort' "${TEST_ROOT}/three.json")"
port_four="$(jq -r '.allocatedPorts[] | select(.name == "web") | .loopbackPort' "${TEST_ROOT}/four.json")"
[[ "${port_three}" != "${port_four}" ]] ||
  fail "concurrent allocations escaped the global lock"
[[ "${port_three}" != "${occupied_port}" ]] ||
  fail "allocator selected a loopback port with an incompatible listener"
kill "${LISTENER_PID}"
wait "${LISTENER_PID}" 2>/dev/null || true
LISTENER_PID=""

[[ "$(find "${MIRROR_ROOT}" -mindepth 1 -maxdepth 1 -type d | wc -l)" -eq 1 ]] ||
  fail "runtime allocation created or removed a mirror"
for session in \
  "${SESSION_ONE}" "${SESSION_TWO}" "${SESSION_THREE}" "${SESSION_FOUR}"; do
  [[ -f "${WORKSPACE_ROOT}/sessions/${session}/${PROJECT}/uncommitted.txt" ]] ||
    fail "runtime allocation removed a synthetic worktree file"
done

echo "Session runtime allocation v1 tests passed."
