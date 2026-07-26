#!/usr/bin/env bash

set -Eeuo pipefail
umask 0007

JSON_MODE=false
if [[ "${1:-}" == "--json" ]]; then
  JSON_MODE=true
  shift
fi
OPERATION="${1:-}"
SESSION_ID="${2:-}"
[[ "$#" -gt 0 ]] && shift
[[ "$#" -gt 0 ]] && shift

TEST_MODE="${ATENEA_RUNTIME_ADMISSION_TEST_MODE:-0}"
SERVICE_USER="${ATENEA_WORKER_SERVICE_USER:-atenea-worker}"
NORMAL_LIMIT=4
HEAVY_LIMIT=2
MIN_MEMORY_AVAILABLE_BYTES=$((8 * 1024 * 1024 * 1024))
MAX_PROCESS_COUNT=8192

usage() {
  cat >&2 <<EOF
Usage:
  $0 [--json] {acquire-normal|acquire-heavy|release-normal|release-heavy} <worksession-uuid>
  $0 [--json] status
EOF
  exit 64
}

[[ "${OPERATION}" =~ ^(acquire-normal|acquire-heavy|release-normal|release-heavy|status)$ ]] ||
  usage
if [[ "${OPERATION}" == "status" ]]; then
  [[ -z "${SESSION_ID}" && "$#" -eq 0 ]] || usage
else
  [[ "${SESSION_ID}" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ &&
      "$#" -eq 0 ]] || usage
fi

for command in awk find flock jq mktemp nproc ps stat; do
  command -v "${command}" >/dev/null || {
    printf 'OPERATION_FAILED: A required admission command is unavailable.\n' >&2
    exit 65
  }
done

if [[ "${TEST_MODE}" == "1" ]]; then
  CONTROL_ROOT="${ATENEA_RUNTIME_ADMISSION_ROOT:-}"
  METRICS_FILE="${ATENEA_RUNTIME_ADMISSION_METRICS_FILE:-}"
  [[ "${CONTROL_ROOT}" == /tmp/* && "${CONTROL_ROOT}" != *".."* ]] ||
    {
      printf 'OPERATION_FAILED: Test admission root must be beneath /tmp.\n' >&2
      exit 65
    }
  [[ -z "${METRICS_FILE}" ||
      ("${METRICS_FILE}" == /tmp/* && "${METRICS_FILE}" != *".."*) ]] ||
    {
      printf 'OPERATION_FAILED: Test metrics must be beneath /tmp.\n' >&2
      exit 65
    }
  EXPECTED_OWNER="$(id -u)"
else
  [[ "$(id -un)" == "${SERVICE_USER}" ]] || {
    printf 'RUNTIME_OWNERSHIP_CONFLICT: Admission must run as %s.\n' \
      "${SERVICE_USER}" >&2
    exit 65
  }
  CONTROL_ROOT="/srv/atenea/worker/runtime-admission-v1"
  METRICS_FILE=""
  EXPECTED_OWNER="$(id -u "${SERVICE_USER}")"
fi

RECORDS_ROOT="${CONTROL_ROOT}/records"
LOCK_PATH="${CONTROL_ROOT}/admission.lock"

assert_owned_directory() {
  local path="$1"
  [[ -d "${path}" && ! -L "${path}" &&
      "$(stat -c %u "${path}")" == "${EXPECTED_OWNER}" ]] || {
    printf 'RUNTIME_OWNERSHIP_CONFLICT: Admission state root is missing, unsafe or foreign-owned.\n' >&2
    exit 65
  }
}

assert_owned_directory "${CONTROL_ROOT}"
if [[ -e "${RECORDS_ROOT}" || -L "${RECORDS_ROOT}" ]]; then
  assert_owned_directory "${RECORDS_ROOT}"
else
  install -d -m 2770 "${RECORDS_ROOT}"
fi
[[ ! -L "${LOCK_PATH}" ]] || {
  printf 'RUNTIME_OWNERSHIP_CONFLICT: Admission lock is a symbolic link.\n' >&2
  exit 65
}
exec {admission_lock_fd}>"${LOCK_PATH}"
[[ -f "${LOCK_PATH}" && ! -L "${LOCK_PATH}" &&
    "$(stat -c %u "${LOCK_PATH}")" == "${EXPECTED_OWNER}" ]] || {
  printf 'RUNTIME_OWNERSHIP_CONFLICT: Admission lock is unsafe or foreign-owned.\n' >&2
  exit 65
}
flock -w 30 "${admission_lock_fd}" || {
  printf 'RECONCILIATION_REQUIRED: Timed out waiting for admission serialization.\n' >&2
  exit 65
}

declare -A SLOT_OWNERS=()
declare -A PERMIT_OWNERS=()
declare -a RECORDS=()
NORMAL_USED=0
HEAVY_USED=0

fail_fixed() {
  local code="$1" message="$2" action="$3" retryable="${4:-false}"
  if [[ "${JSON_MODE}" == "true" ]]; then
    jq -cn \
      --arg operation "${OPERATION}" \
      --arg session "${SESSION_ID}" \
      --arg code "${code}" \
      --arg message "${message}" \
      --arg action "${action}" \
      --argjson retryable "${retryable}" \
      --argjson normalUsed "${NORMAL_USED}" \
      --argjson heavyUsed "${HEAVY_USED}" '{
        schemaVersion: 1,
        operation: $operation,
        state: "blocked",
        capacity: {
          normalUsed: $normalUsed,
          normalLimit: 4,
          heavyUsed: $heavyUsed,
          heavyLimit: 2
        },
        error: {
          code: $code,
          message: $message,
          retryable: $retryable,
          action: $action
        }
      } + (if $session == "" then {} else {sessionId: $session} end)'
  fi
  printf '%s: %s\nNext action: %s\n' "${code}" "${message}" "${action}" >&2
  exit 65
}

validate_record() {
  local record="$1" expected_session
  expected_session="$(basename -- "${record}" .json)"
  [[ "${expected_session}" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ &&
      -f "${record}" && ! -L "${record}" &&
      "$(stat -c %u "${record}")" == "${EXPECTED_OWNER}" &&
      "$(stat -c %a "${record}")" =~ ^6[04]0$ ]] ||
    fail_fixed "RUNTIME_OWNERSHIP_CONFLICT" \
      "An admission record is unsafe or foreign-owned." \
      "Reconcile the persisted admission records without deleting session evidence."
  jq -e --arg session "${expected_session}" '
    (keys | sort) == ["heavy", "normal", "schemaVersion", "sessionId"] and
    .schemaVersion == 1 and .sessionId == $session and
    (.normal | type == "object" and
      (keys | sort) == ["slot", "state"] and
      (.slot | test("^slot[1-4]$")) and
      (.state == "held" or .state == "released")) and
    (.heavy == null or
      (.heavy | type == "object" and
        (keys | sort) == ["permit", "state"] and
        (.permit | test("^heavy[1-2]$")) and
        (.state == "held" or .state == "released")))
  ' "${record}" >/dev/null ||
    fail_fixed "RUNTIME_OWNERSHIP_CONFLICT" \
      "An admission record conflicts with its persisted WorkSession identity." \
      "Inspect and repair the record before accepting more work."
}

scan_records() {
  SLOT_OWNERS=()
  PERMIT_OWNERS=()
  RECORDS=()
  NORMAL_USED=0
  HEAVY_USED=0
  if find "${RECORDS_ROOT}" -mindepth 1 -maxdepth 1 -type l -print -quit |
      grep -q .; then
    fail_fixed "RUNTIME_OWNERSHIP_CONFLICT" \
      "An admission record is a symbolic link." \
      "Inspect the link without following or replacing it."
  fi
  mapfile -d '' RECORDS < <(
    find "${RECORDS_ROOT}" -mindepth 1 -maxdepth 1 \
      -name '*.json' -type f -print0 | sort -z
  )
  local record owner slot permit
  for record in "${RECORDS[@]}"; do
    validate_record "${record}"
    owner="$(jq -r '.sessionId' "${record}")"
    if [[ "$(jq -r '.normal.state' "${record}")" == "held" ]]; then
      slot="$(jq -r '.normal.slot' "${record}")"
      [[ -z "${SLOT_OWNERS[${slot}]:-}" ]] ||
        fail_fixed "RUNTIME_OWNERSHIP_CONFLICT" \
          "Two WorkSessions claim the same normal slot." \
          "Reconcile the conflicting records before accepting more work."
      SLOT_OWNERS["${slot}"]="${owner}"
      NORMAL_USED=$((NORMAL_USED + 1))
    fi
    if [[ "$(jq -r '.heavy.state // "released"' "${record}")" == "held" ]]; then
      permit="$(jq -r '.heavy.permit' "${record}")"
      [[ -z "${PERMIT_OWNERS[${permit}]:-}" ]] ||
        fail_fixed "RUNTIME_OWNERSHIP_CONFLICT" \
          "Two WorkSessions claim the same heavy permit." \
          "Reconcile the conflicting records before accepting more work."
      PERMIT_OWNERS["${permit}"]="${owner}"
      HEAVY_USED=$((HEAVY_USED + 1))
    fi
  done
}

PRESSURE_STATE="ready"
PRESSURE_REASON="ready"
LOAD_MILLI=0
MAX_LOAD_MILLI=0
MEMORY_AVAILABLE_BYTES=0
PROCESS_COUNT=0

read_pressure() {
  local cpu_count
  if [[ -n "${METRICS_FILE}" ]]; then
    [[ -f "${METRICS_FILE}" && ! -L "${METRICS_FILE}" ]] ||
      fail_fixed "RUNTIME_OWNERSHIP_CONFLICT" \
        "Synthetic pressure metrics are missing or unsafe." \
        "Restore the owned metrics fixture beneath /tmp."
    jq -e '
      (keys | sort) == [
        "cpuCount", "loadMilli", "memoryAvailableBytes", "processCount"
      ] and all(.[]; type == "number" and floor == . and . >= 0) and
      .cpuCount >= 1
    ' "${METRICS_FILE}" >/dev/null ||
      fail_fixed "OPERATION_FAILED" \
        "Pressure metrics are incompatible with admission v1." \
        "Regenerate the bounded metrics input and retry."
    cpu_count="$(jq -r '.cpuCount' "${METRICS_FILE}")"
    LOAD_MILLI="$(jq -r '.loadMilli' "${METRICS_FILE}")"
    MEMORY_AVAILABLE_BYTES="$(jq -r '.memoryAvailableBytes' "${METRICS_FILE}")"
    PROCESS_COUNT="$(jq -r '.processCount' "${METRICS_FILE}")"
  else
    cpu_count="$(nproc)"
    LOAD_MILLI="$(
      awk '{printf "%d\n", ($1 * 1000)}' /proc/loadavg
    )"
    MEMORY_AVAILABLE_BYTES="$(
      awk '/^MemAvailable:/ {printf "%.0f\n", ($2 * 1024); exit}' /proc/meminfo
    )"
    PROCESS_COUNT="$(ps -e --no-headers | wc -l)"
  fi
  MAX_LOAD_MILLI=$((cpu_count * 750))
  PRESSURE_STATE="ready"
  PRESSURE_REASON="ready"
  if [[ "${MEMORY_AVAILABLE_BYTES}" -lt "${MIN_MEMORY_AVAILABLE_BYTES}" ]]; then
    PRESSURE_STATE="blocked"
    PRESSURE_REASON="memory-headroom"
  elif [[ "${LOAD_MILLI}" -gt "${MAX_LOAD_MILLI}" ]]; then
    PRESSURE_STATE="blocked"
    PRESSURE_REASON="cpu-load"
  elif [[ "${PROCESS_COUNT}" -gt "${MAX_PROCESS_COUNT}" ]]; then
    PRESSURE_STATE="blocked"
    PRESSURE_REASON="process-headroom"
  fi
}

write_record() {
  local document="$1" temporary
  temporary="$(mktemp "${RECORDS_ROOT}/.${SESSION_ID}.json.XXXXXX")"
  jq -cS '.' <<<"${document}" >"${temporary}"
  chmod 0640 "${temporary}"
  mv -- "${temporary}" "${RECORDS_ROOT}/${SESSION_ID}.json"
}

emit_admitted() {
  local record="$1" state="${2:-admitted}"
  if [[ "${JSON_MODE}" == "true" ]]; then
    jq -cn \
      --arg operation "${OPERATION}" \
      --arg state "${state}" \
      --argjson record "$(jq -c '.' "${record}")" \
      --argjson normalUsed "${NORMAL_USED}" \
      --argjson heavyUsed "${HEAVY_USED}" '{
        schemaVersion: 1,
        operation: $operation,
        state: $state,
        sessionId: $record.sessionId,
        record: $record,
        capacity: {
          normalUsed: $normalUsed,
          normalLimit: 4,
          heavyUsed: $heavyUsed,
          heavyLimit: 2
        }
      }'
  else
    printf 'WorkSession: %s\n' "$(jq -r '.sessionId' "${record}")"
    printf 'Normal slot: %s (%s)\n' \
      "$(jq -r '.normal.slot' "${record}")" \
      "$(jq -r '.normal.state' "${record}")"
    if [[ "$(jq -r '.heavy // empty' "${record}")" != "" ]]; then
      printf 'Heavy permit: %s (%s)\n' \
        "$(jq -r '.heavy.permit' "${record}")" \
        "$(jq -r '.heavy.state' "${record}")"
    fi
  fi
}

emit_status() {
  read_pressure
  if [[ "${JSON_MODE}" == "true" ]]; then
    jq -cn \
      --argjson normalUsed "${NORMAL_USED}" \
      --argjson heavyUsed "${HEAVY_USED}" \
      --arg pressureState "${PRESSURE_STATE}" \
      --arg pressureReason "${PRESSURE_REASON}" \
      --argjson loadMilli "${LOAD_MILLI}" \
      --argjson maxLoadMilli "${MAX_LOAD_MILLI}" \
      --argjson memoryAvailableBytes "${MEMORY_AVAILABLE_BYTES}" \
      --argjson processCount "${PROCESS_COUNT}" '{
        schemaVersion: 1,
        operation: "status",
        state: (if $pressureState == "ready" then "ready" else "blocked" end),
        capacity: {
          normalUsed: $normalUsed,
          normalLimit: 4,
          heavyUsed: $heavyUsed,
          heavyLimit: 2
        },
        pressure: {
          state: $pressureState,
          reason: $pressureReason,
          loadMilli: $loadMilli,
          maxLoadMilli: $maxLoadMilli,
          memoryAvailableBytes: $memoryAvailableBytes,
          minimumMemoryAvailableBytes: 8589934592,
          processCount: $processCount,
          maximumProcessCount: 8192
        }
      }'
  else
    printf 'Normal slots: %s/%s\n' "${NORMAL_USED}" "${NORMAL_LIMIT}"
    printf 'Heavy permits: %s/%s\n' "${HEAVY_USED}" "${HEAVY_LIMIT}"
    printf 'Resource pressure: %s (%s)\n' \
      "${PRESSURE_STATE}" "${PRESSURE_REASON}"
  fi
}

scan_records
CURRENT_RECORD="${RECORDS_ROOT}/${SESSION_ID}.json"

case "${OPERATION}" in
  status)
    emit_status
    ;;
  acquire-normal)
    if [[ -f "${CURRENT_RECORD}" &&
        "$(jq -r '.normal.state' "${CURRENT_RECORD}")" == "held" ]]; then
      emit_admitted "${CURRENT_RECORD}"
      exit 0
    fi
    read_pressure
    if [[ "${PRESSURE_STATE}" == "blocked" ]]; then
      fail_fixed "NORMAL_CAPACITY_EXHAUSTED" \
        "Host recovery headroom is below the normal-admission threshold." \
        "Wait for resource pressure to clear, then retry the same WorkSession." true
    fi
    selected_slot=""
    for candidate in slot1 slot2 slot3 slot4; do
      if [[ -z "${SLOT_OWNERS[${candidate}]:-}" ]]; then
        selected_slot="${candidate}"
        break
      fi
    done
    [[ -n "${selected_slot}" ]] ||
      fail_fixed "NORMAL_CAPACITY_EXHAUSTED" \
        "All four normal runtime slots are owned." \
        "Wait for a WorkSession to release its slot, then retry unchanged." true
    if [[ -f "${CURRENT_RECORD}" ]]; then
      document="$(
        jq -c --arg slot "${selected_slot}" \
          '.normal = {slot: $slot, state: "held"}' "${CURRENT_RECORD}"
      )"
    else
      document="$(
        jq -cn --arg session "${SESSION_ID}" --arg slot "${selected_slot}" '{
          schemaVersion: 1,
          sessionId: $session,
          normal: {slot: $slot, state: "held"},
          heavy: null
        }'
      )"
    fi
    write_record "${document}"
    scan_records
    emit_admitted "${CURRENT_RECORD}"
    ;;
  acquire-heavy)
    [[ -f "${CURRENT_RECORD}" &&
        "$(jq -r '.normal.state' "${CURRENT_RECORD}")" == "held" ]] ||
      fail_fixed "RUNTIME_OWNERSHIP_CONFLICT" \
        "A heavy operation requires this WorkSession to own a normal slot." \
        "Acquire or recover the WorkSession normal slot before retrying."
    if [[ "$(jq -r '.heavy.state // "released"' "${CURRENT_RECORD}")" == "held" ]]; then
      emit_admitted "${CURRENT_RECORD}"
      exit 0
    fi
    read_pressure
    if [[ "${PRESSURE_STATE}" == "blocked" ]]; then
      fail_fixed "HEAVY_CAPACITY_EXHAUSTED" \
        "Host recovery headroom is below the heavy-admission threshold." \
        "Wait for resource pressure to clear, then retry the same operation." true
    fi
    selected_permit=""
    for candidate in heavy1 heavy2; do
      if [[ -z "${PERMIT_OWNERS[${candidate}]:-}" ]]; then
        selected_permit="${candidate}"
        break
      fi
    done
    [[ -n "${selected_permit}" ]] ||
      fail_fixed "HEAVY_CAPACITY_EXHAUSTED" \
        "Both heavy-operation permits are owned." \
        "Wait for a heavy operation to release its permit, then retry unchanged." true
    document="$(
      jq -c --arg permit "${selected_permit}" \
        '.heavy = {permit: $permit, state: "held"}' "${CURRENT_RECORD}"
    )"
    write_record "${document}"
    scan_records
    emit_admitted "${CURRENT_RECORD}"
    ;;
  release-heavy)
    [[ -f "${CURRENT_RECORD}" ]] ||
      fail_fixed "RUNTIME_OWNERSHIP_CONFLICT" \
        "The WorkSession has no persisted admission record." \
        "Reconcile the WorkSession identity before releasing capacity."
    if [[ "$(jq -r '.heavy.state // "released"' "${CURRENT_RECORD}")" == "held" ]]; then
      document="$(jq -c '.heavy.state = "released"' "${CURRENT_RECORD}")"
      write_record "${document}"
      scan_records
    fi
    emit_admitted "${CURRENT_RECORD}" "released"
    ;;
  release-normal)
    [[ -f "${CURRENT_RECORD}" ]] ||
      fail_fixed "RUNTIME_OWNERSHIP_CONFLICT" \
        "The WorkSession has no persisted admission record." \
        "Reconcile the WorkSession identity before releasing capacity."
    [[ "$(jq -r '.heavy.state // "released"' "${CURRENT_RECORD}")" != "held" ]] ||
      fail_fixed "RUNTIME_OWNERSHIP_CONFLICT" \
        "The WorkSession still owns a heavy permit." \
        "Release the heavy permit before releasing its normal slot."
    if [[ "$(jq -r '.normal.state' "${CURRENT_RECORD}")" == "held" ]]; then
      document="$(jq -c '.normal.state = "released"' "${CURRENT_RECORD}")"
      write_record "${document}"
      scan_records
    fi
    emit_admitted "${CURRENT_RECORD}" "released"
    ;;
esac
