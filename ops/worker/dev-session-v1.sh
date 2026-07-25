#!/usr/bin/env bash

set -Eeuo pipefail
umask 0007

TEST_MODE="${ATENEA_DEV_TEST_MODE:-0}"
SERVICE_USER="${ATENEA_WORKER_SERVICE_USER:-atenea-worker}"
SESSION_ID=""
PROJECT_FILTER=""
OPERATION=""
LOG_TAIL="200"
TAIL_SET=false

fail() {
  local code="$1" message="$2" action="$3"
  printf '%s: %s\nNext action: %s\n' "${code}" "${message}" "${action}" >&2
  exit 65
}

usage() {
  cat >&2 <<EOF
Usage:
  dev [--session <worksession-uuid>] [--tail <lines>] \
    {list|status|build|up|stop|restart|redeploy|logs|url|doctor} [project]

Task 3.3 provides human output only. Machine-readable --json output is task 3.4.
EOF
  exit 64
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --session)
      [[ "$#" -ge 2 && -z "${SESSION_ID}" ]] || usage
      SESSION_ID="$2"
      shift 2
      ;;
    --tail)
      [[ "$#" -ge 2 && "${TAIL_SET}" == "false" ]] || usage
      LOG_TAIL="$2"
      TAIL_SET=true
      shift 2
      ;;
    --json)
      fail "OPERATION_FAILED" "Machine-readable dev output is not implemented in task 3.3." \
        "Use the human command now or continue with the reviewed task 3.4 contract."
      ;;
    -*)
      usage
      ;;
    *)
      if [[ -z "${OPERATION}" ]]; then
        OPERATION="$1"
      elif [[ -z "${PROJECT_FILTER}" ]]; then
        PROJECT_FILTER="$1"
      else
        usage
      fi
      shift
      ;;
  esac
done

case "${OPERATION}" in
  list|status|build|up|stop|restart|redeploy|logs|url|doctor)
    ;;
  *)
    usage
    ;;
esac
[[ -z "${SESSION_ID}" ||
    "${SESSION_ID}" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] ||
  fail "SESSION_REQUIRED" "Session identity is not a canonical lowercase UUID." \
    "Retry with the persisted WorkSession UUID."
[[ -z "${PROJECT_FILTER}" ||
    "${PROJECT_FILTER}" =~ ^[a-z][a-z0-9-]{1,62}$ ]] ||
  fail "SESSION_IDENTITY_CONFLICT" "Project selector is invalid." \
    "Use the registered lowercase project identifier."
[[ "${LOG_TAIL}" =~ ^[0-9]+$ &&
    "${LOG_TAIL}" -ge 1 && "${LOG_TAIL}" -le 10000 ]] ||
  fail "OPERATION_FAILED" "Log tail must be between 1 and 10000 lines." \
    "Retry with a bounded --tail value."
if [[ "${TAIL_SET}" == "true" && "${OPERATION}" != "logs" ]]; then
  fail "OPERATION_FAILED" "--tail is valid only for dev logs." \
    "Remove --tail or select the logs operation."
fi

for command in find jq realpath sort stat timeout; do
  command -v "${command}" >/dev/null ||
    fail "OPERATION_FAILED" "Required command is unavailable: ${command}" \
      "Install the version-pinned worker prerequisites and retry."
done

if [[ "${TEST_MODE}" == "1" ]]; then
  WORKSPACE_ROOT="${ATENEA_WORKSPACE_ROOT:-}"
  RUNTIME_CLIENT="${ATENEA_RUNTIME_CLIENT:-}"
  [[ "${WORKSPACE_ROOT}" == /tmp/* && "${WORKSPACE_ROOT}" != *".."* ]] ||
    fail "OPERATION_FAILED" "Test workspace root must be an explicit path beneath /tmp." \
      "Use a fresh synthetic test directory."
  [[ "${RUNTIME_CLIENT}" == /tmp/* && "${RUNTIME_CLIENT}" != *".."* ]] ||
    fail "OPERATION_FAILED" "Test runtime client must be an explicit path beneath /tmp." \
      "Use the synthetic runtime client fixture."
else
  [[ "$(id -un)" == "${SERVICE_USER}" ]] ||
    fail "OPERATION_FAILED" "Managed dev must run as ${SERVICE_USER}." \
      "Invoke dev through the worker service identity."
  WORKSPACE_ROOT="/srv/atenea/workspaces"
  RUNTIME_CLIENT="/usr/libexec/atenea-runtime-client-v1"
fi

[[ -d "${WORKSPACE_ROOT}" && ! -L "${WORKSPACE_ROOT}" &&
    "$(stat -c %u "${WORKSPACE_ROOT}")" == "$(id -u)" ]] ||
  fail "RUNTIME_OWNERSHIP_CONFLICT" "Workspace root is missing, unsafe or foreign-owned." \
    "Restore the worker-owned filesystem skeleton before using dev."
SESSIONS_ROOT="${WORKSPACE_ROOT}/sessions"
if [[ -e "${SESSIONS_ROOT}" || -L "${SESSIONS_ROOT}" ]]; then
  [[ -d "${SESSIONS_ROOT}" && ! -L "${SESSIONS_ROOT}" &&
      "$(stat -c %u "${SESSIONS_ROOT}")" == "$(id -u)" ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "Session root is unsafe or foreign-owned." \
      "Reconcile session ownership before using dev."
fi

runtime_client_available() {
  [[ -f "${RUNTIME_CLIENT}" && ! -L "${RUNTIME_CLIENT}" &&
      -x "${RUNTIME_CLIENT}" ]] || return 1
  if [[ "${TEST_MODE}" == "1" ]]; then
    [[ "$(stat -c %u "${RUNTIME_CLIENT}")" == "$(id -u)" ]]
  else
    [[ "$(stat -c %u "${RUNTIME_CLIENT}")" == "0" &&
        "$(stat -c %a "${RUNTIME_CLIENT}")" =~ ^[57][0-5][0-5]$ ]]
  fi
}

validate_record() {
  local record="$1"
  local session_root expected_session workspace_record
  local project worktree manifest_relative worktree_real manifest_real

  [[ -f "${record}" && ! -L "${record}" &&
      "$(stat -c %u "${record}")" == "$(id -u)" &&
      "$(stat -c %a "${record}")" =~ ^6[04]0$ ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "A runtime allocation record is unsafe or foreign-owned." \
      "Reconcile the worker-owned record without changing project files."
  session_root="$(dirname -- "${record}")"
  expected_session="$(basename -- "${session_root}")"
  [[ "${expected_session}" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "A runtime allocation is outside a canonical session directory." \
      "Quarantine the incompatible record after proving ownership."
  [[ -d "${session_root}" && ! -L "${session_root}" &&
      "$(stat -c %u "${session_root}")" == "$(id -u)" ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "A session directory is unsafe or foreign-owned." \
      "Reconcile the session root before using dev."

  jq -e \
    --arg session "${expected_session}" '
      .schemaVersion == 1 and .state == "allocated" and
      .sessionId == $session and
      (.projectId | test("^[a-z][a-z0-9-]{1,62}$")) and
      .runtimeId == ("ws-" + ($session | gsub("-"; ""))) and
      (.manifestRelativePath |
        test("^(?!/)(?!~)(?!.*(?:^|/)\\.\\.(?:/|$))(?!.*//)[A-Za-z0-9._/-]+$")) and
      (.slot | test("^slot[1-4]$")) and
      .workloadClass == "normal" and
      (.allocatedPorts | type == "array" and length > 0) and
      ([.allocatedPorts[].name] | length == (unique | length)) and
      ([.allocatedPorts[].loopbackPort] | length == (unique | length)) and
      all(.allocatedPorts[];
        (.name | test("^[a-z][a-z0-9-]{1,62}$")) and
        (.internalPort | type == "number" and
          . >= 1 and . <= 65535 and floor == .) and
        (.protocol == "http" or .protocol == "tcp") and
        .bindAddress == "127.0.0.1" and
        (.loopbackPort | type == "number" and
          . >= 1024 and . <= 65535 and floor == .))
    ' "${record}" >/dev/null ||
    fail "RECONCILIATION_REQUIRED" "A runtime allocation record is invalid or incompatible." \
      "Reconcile the allocation before using dev."

  project="$(jq -r '.projectId' "${record}")"
  worktree="$(jq -r '.worktreePath' "${record}")"
  manifest_relative="$(jq -r '.manifestRelativePath' "${record}")"
  [[ "${worktree}" == "${session_root}/${project}" &&
      -d "${worktree}" && ! -L "${worktree}" &&
      "$(stat -c %u "${worktree}")" == "$(id -u)" ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "The allocated worktree is missing, unsafe or foreign-owned." \
      "Reconcile task 3.1 without resetting or cleaning source."

  workspace_record="${session_root}/workspace-v1.json"
  [[ -f "${workspace_record}" && ! -L "${workspace_record}" &&
      "$(stat -c %u "${workspace_record}")" == "$(id -u)" &&
      "$(stat -c %a "${workspace_record}")" =~ ^6[04]0$ ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "The workspace ownership record is unsafe or missing." \
      "Reconcile task 3.1 before using dev."
  jq -e \
    --arg session "${expected_session}" \
    --arg project "${project}" \
    --arg worktree "${worktree}" \
    --arg branch "$(jq -r '.branch' "${record}")" \
    --arg mirror "$(jq -r '.mirrorPath' "${record}")" '
      .schemaVersion == 1 and .state == "ready" and
      .sessionId == $session and .projectId == $project and
      .worktreePath == $worktree and .branch == $branch and
      .mirrorPath == $mirror
    ' "${workspace_record}" >/dev/null ||
    fail "SESSION_IDENTITY_CONFLICT" "Workspace and runtime allocation identities do not match." \
      "Reconcile the persisted session records before using dev."

  worktree_real="$(realpath -e "${worktree}")"
  [[ -f "${worktree}/${manifest_relative}" &&
      ! -L "${worktree}/${manifest_relative}" ]] ||
    fail "MANIFEST_INVALID" "The allocated runtime manifest is missing or is a symbolic link." \
      "Restore the reviewed manifest at its persisted repository-relative path."
  manifest_real="$(realpath -e "${worktree}/${manifest_relative}")"
  [[ "${manifest_real}" == "${worktree_real}/"* ]] ||
    fail "MANIFEST_INVALID" "The allocated runtime manifest escapes the session worktree." \
      "Restore the reviewed repository-relative manifest."
  jq -e \
    --arg project "${project}" \
    --argjson ports "$(jq -cS \
      '[.allocatedPorts[] | {
        name: .name,
        internalPort: .internalPort,
        protocol: .protocol
      }] | sort_by(.name)' "${record}")" '
      .schemaVersion == 1 and .project.id == $project and
      .workloadClass == "normal" and
      ([.runtime.internalPorts[] | {
        name: .name,
        internalPort: .port,
        protocol: .protocol
      }] | sort_by(.name)) == $ports and
      (.preview.internalPort | type == "string") and
      .preview.internalPort as $previewPort |
      any(.runtime.internalPorts[]; .name == $previewPort)
    ' "${manifest_real}" >/dev/null ||
    fail "MANIFEST_INVALID" "The allocated manifest no longer matches its runtime identity or ports." \
      "Restore the reviewed manifest or reconcile the allocation explicitly."
}

declare -a RECORDS=()
if [[ -d "${SESSIONS_ROOT}" ]]; then
  if find "${SESSIONS_ROOT}" -mindepth 2 -maxdepth 2 \
    -name runtime-allocation-v1.json -type l -print -quit | grep -q .; then
    fail "RUNTIME_OWNERSHIP_CONFLICT" "A runtime allocation record is a symbolic link." \
      "Inspect the link without following or replacing it."
  fi
  mapfile -d '' RECORDS < <(
    find "${SESSIONS_ROOT}" -mindepth 2 -maxdepth 2 \
      -name runtime-allocation-v1.json -type f -print0 |
      sort -z
  )
fi
for record in "${RECORDS[@]}"; do
  validate_record "${record}"
done

record_summary() {
  local record="$1"
  jq -r '[.projectId, .sessionId, .slot, .state] | @tsv' "${record}"
}

print_list() {
  local record
  if [[ "${#RECORDS[@]}" -eq 0 ]]; then
    echo "No WorkSessions have a runtime allocation."
    return
  fi
  printf 'PROJECT\tSESSION\tSLOT\tSTATE\n'
  for record in "${RECORDS[@]}"; do
    if [[ -z "${PROJECT_FILTER}" ||
          "$(jq -r '.projectId' "${record}")" == "${PROJECT_FILTER}" ]]; then
      record_summary "${record}"
    fi
  done
}

SELECTED_RECORD=""
select_record() {
  local record cwd_real worktree_real match_count=0

  if [[ -n "${SESSION_ID}" ]]; then
    SELECTED_RECORD="${SESSIONS_ROOT}/${SESSION_ID}/runtime-allocation-v1.json"
    [[ -f "${SELECTED_RECORD}" ]] ||
      fail "SESSION_REQUIRED" "The selected WorkSession has no runtime allocation." \
        "Allocate the session or select an existing WorkSession."
    validate_record "${SELECTED_RECORD}"
  else
    cwd_real="$(realpath -e "${PWD}")"
    for record in "${RECORDS[@]}"; do
      worktree_real="$(realpath -e "$(jq -r '.worktreePath' "${record}")")"
      if [[ "${cwd_real}" == "${worktree_real}" ||
            "${cwd_real}" == "${worktree_real}/"* ]]; then
        SELECTED_RECORD="${record}"
        match_count=$((match_count + 1))
      fi
    done
    if [[ "${match_count}" -gt 1 ]]; then
      fail "SESSION_AMBIGUOUS" "Current directory matches more than one WorkSession." \
        "Retry with --session and the intended WorkSession UUID."
    fi

    if [[ -z "${SELECTED_RECORD}" && -n "${PROJECT_FILTER}" ]]; then
      for record in "${RECORDS[@]}"; do
        if [[ "$(jq -r '.projectId' "${record}")" == "${PROJECT_FILTER}" ]]; then
          SELECTED_RECORD="${record}"
          match_count=$((match_count + 1))
        fi
      done
      if [[ "${match_count}" -gt 1 ]]; then
        fail "SESSION_AMBIGUOUS" "The project has more than one allocated WorkSession." \
          "Retry with --session and the intended WorkSession UUID."
      fi
    fi
  fi

  [[ -n "${SELECTED_RECORD}" ]] ||
    fail "SESSION_REQUIRED" "No WorkSession can be resolved for this operation." \
      "Run inside the session worktree or pass --session explicitly."
  if [[ -n "${PROJECT_FILTER}" &&
        "$(jq -r '.projectId' "${SELECTED_RECORD}")" != "${PROJECT_FILTER}" ]]; then
    fail "SESSION_IDENTITY_CONFLICT" "Project selector does not match the selected WorkSession." \
      "Use the session's persisted project or select another WorkSession."
  fi
}

selected_manifest() {
  local worktree relative
  worktree="$(jq -r '.worktreePath' "${SELECTED_RECORD}")"
  relative="$(jq -r '.manifestRelativePath' "${SELECTED_RECORD}")"
  printf '%s/%s\n' "${worktree}" "${relative}"
}

selected_url() {
  local manifest preview_name preview_path port
  manifest="$(selected_manifest)"
  preview_name="$(jq -r '.preview.internalPort' "${manifest}")"
  preview_path="$(jq -r '.preview.path' "${manifest}")"
  port="$(
    jq -r \
      --arg name "${preview_name}" \
      '.allocatedPorts[] | select(.name == $name) | .loopbackPort' \
      "${SELECTED_RECORD}"
  )"
  [[ "${port}" =~ ^[0-9]+$ ]] ||
    fail "RECONCILIATION_REQUIRED" "Preview port is absent from the runtime allocation." \
      "Reconcile the manifest and allocation before using the URL."
  printf 'http://127.0.0.1:%s%s\n' "${port}" "${preview_path}"
}

invoke_runtime_client() {
  local operation="$1"
  shift
  runtime_client_available ||
    fail "OPERATION_FAILED" "The mediated runtime client is not installed." \
      "Complete and accept task 4.2 before executing project lifecycle operations."
  timeout --foreground 3600 \
    "${RUNTIME_CLIENT}" "${operation}" \
      --session "$(jq -r '.sessionId' "${SELECTED_RECORD}")" \
      --allocation "${SELECTED_RECORD}" \
      --manifest "$(selected_manifest)" \
      "$@"
}

print_selected_status() {
  printf 'Project: %s\n' "$(jq -r '.projectId' "${SELECTED_RECORD}")"
  printf 'WorkSession: %s\n' "$(jq -r '.sessionId' "${SELECTED_RECORD}")"
  printf 'Allocation: %s\n' "$(jq -r '.state' "${SELECTED_RECORD}")"
  printf 'Slot: %s\n' "$(jq -r '.slot' "${SELECTED_RECORD}")"
  printf 'Runtime: %s\n' "$(jq -r '.runtimeId' "${SELECTED_RECORD}")"
  printf 'URL: %s\n' "$(selected_url)"
  if runtime_client_available; then
    echo "Runtime client: ready"
    invoke_runtime_client status
  else
    echo "Runtime client: blocked (task 4.2 pending)"
  fi
}

case "${OPERATION}" in
  list)
    [[ -z "${SESSION_ID}" && "${TAIL_SET}" == "false" ]] || usage
    print_list
    ;;
  status)
    if [[ -z "${SESSION_ID}" && -z "${PROJECT_FILTER}" ]]; then
      cwd_selected=false
      for record in "${RECORDS[@]}"; do
        worktree="$(realpath -e "$(jq -r '.worktreePath' "${record}")")"
        if [[ "$(realpath -e "${PWD}")" == "${worktree}" ||
              "$(realpath -e "${PWD}")" == "${worktree}/"* ]]; then
          cwd_selected=true
          break
        fi
      done
      if [[ "${cwd_selected}" == "false" ]]; then
        print_list
        exit 0
      fi
    fi
    select_record
    print_selected_status
    ;;
  doctor)
    echo "Workspace root: ready"
    printf 'Validated allocations: %s\n' "${#RECORDS[@]}"
    if runtime_client_available; then
      echo "Runtime client: ready"
      if [[ -n "${SESSION_ID}" || -n "${PROJECT_FILTER}" ]]; then
        select_record
        invoke_runtime_client doctor
      fi
    else
      echo "Runtime client: blocked"
      fail "OPERATION_FAILED" "The mediated runtime client is not installed." \
        "Complete and accept task 4.2 before executing project lifecycle operations."
    fi
    ;;
  url)
    select_record
    selected_url
    ;;
  logs)
    select_record
    invoke_runtime_client logs --tail "${LOG_TAIL}"
    ;;
  build|up|stop|restart|redeploy)
    select_record
    printf 'Project: %s\n' "$(jq -r '.projectId' "${SELECTED_RECORD}")"
    printf 'WorkSession: %s\n' "$(jq -r '.sessionId' "${SELECTED_RECORD}")"
    printf 'Operation: %s\n' "${OPERATION}"
    invoke_runtime_client "${OPERATION}"
    ;;
esac
