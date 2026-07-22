#!/usr/bin/env bash
set -uo pipefail

MODE=human
STRICT=false
WORKER_HOSTNAME="${ATENEA_WORKER_HOSTNAME:-codex-worker-01}"
for argument in "$@"; do
  case "${argument}" in
    --json) MODE=json ;;
    --strict) STRICT=true ;;
    *) echo "Unknown argument: ${argument}" >&2; exit 64 ;;
  esac
done

declare -a NAMES=() STATES=() DETAILS=()
FAILURES=0

record() {
  local name="$1" state="$2" detail="$3"
  NAMES+=("${name}") STATES+=("${state}") DETAILS+=("${detail}")
  [[ "${state}" == pass ]] || FAILURES=$((FAILURES + 1))
}

[[ "$(hostname)" == "${WORKER_HOSTNAME}" ]] && record hostname pass "${WORKER_HOSTNAME}" || record hostname fail "expected=${WORKER_HOSTNAME} actual=$(hostname)"
id jose >/dev/null 2>&1 && id -nG jose | grep -qw sudo && record admin pass "jose in sudo" || record admin fail "jose/sudo missing"
id atenea-worker >/dev/null 2>&1 && [[ "$(getent passwd atenea-worker | cut -d: -f7)" == /usr/sbin/nologin ]] && record service_account pass "atenea-worker nologin" || record service_account fail "account or shell invalid"

PATHS_OK=true
for path in /srv/atenea/worker /srv/atenea/repositories /srv/atenea/workspaces /srv/atenea/caches /srv/atenea/artifacts /srv/atenea/backups-staging /etc/atenea-worker; do
  [[ -d "${path}" ]] || PATHS_OK=false
done
${PATHS_OK} && record paths pass "required skeleton present" || record paths fail "one or more paths missing"

if sshd -t 2>/dev/null; then
  SSH_EFFECTIVE="$(sshd -T 2>/dev/null)"
  SSH_OK=true
  grep -qx 'passwordauthentication no' <<<"${SSH_EFFECTIVE}" || SSH_OK=false
  grep -qx 'kbdinteractiveauthentication no' <<<"${SSH_EFFECTIVE}" || SSH_OK=false
  grep -qx 'permitrootlogin without-password' <<<"${SSH_EFFECTIVE}" || grep -qx 'permitrootlogin prohibit-password' <<<"${SSH_EFFECTIVE}" || SSH_OK=false
  grep -qx 'x11forwarding no' <<<"${SSH_EFFECTIVE}" || SSH_OK=false
  ${SSH_OK} && record sshd pass "key-only; root break-glass; X11 off" || record sshd fail "effective policy differs"
else
  record sshd fail "syntax invalid"
fi

if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q '^Status: active'; then
  UFW_STATUS="$(ufw status 2>/dev/null)"
  grep -Eq '^22/tcp[[:space:]].*LIMIT' <<<"${UFW_STATUS}" && record firewall pass "active; SSH limited" || record firewall fail "active without SSH limit"
else
  record firewall fail "inactive"
fi

MDSTAT="$(cat /proc/mdstat 2>/dev/null)"
if [[ "${MDSTAT}" == *"[UU]"* ]] && [[ "${MDSTAT}" != *"[_U]"* ]] && [[ "${MDSTAT}" != *"[U_]"* ]] && ! grep -Eq 'resync|recovery|reshape|check[[:space:]]*=' <<<"${MDSTAT}"; then
  record raid pass "arrays healthy [UU], no active operation"
else
  record raid fail "degraded or active RAID operation"
fi

SMART_OK=true
SMART_DETAIL=""
for device in /dev/nvme0n1 /dev/nvme1n1; do
  if [[ -b "${device}" ]] && smartctl -H "${device}" 2>/dev/null | grep -Eq 'PASSED|OK'; then
    SMART_DETAIL+="${device}=pass "
  else
    SMART_OK=false
    SMART_DETAIL+="${device}=fail "
  fi
done
${SMART_OK} && record smart pass "${SMART_DETAIL% }" || record smart fail "${SMART_DETAIL% }"

ROOT_USED="$(df -P / | awk 'NR==2 {gsub(/%/, "", $5); print $5}')"
[[ "${ROOT_USED}" =~ ^[0-9]+$ ]] && (( ROOT_USED < 90 )) && record capacity pass "root_used=${ROOT_USED}%" || record capacity fail "root_used=${ROOT_USED:-unknown}%"

[[ "$(timedatectl show -p NTPSynchronized --value 2>/dev/null)" == yes ]] && record time pass "NTP synchronized" || record time fail "NTP not synchronized"
systemctl is-active --quiet unattended-upgrades.service && record security_updates pass "service active; automatic reboot disabled" || record security_updates fail "service inactive"

if command -v tailscale >/dev/null; then
  if tailscale status --json 2>/dev/null | jq -e '.BackendState == "Running"' >/dev/null 2>&1; then
    record tailscale pass "enrolled and running"
  elif [[ -f /etc/atenea-worker/gates/tailscale-enrollment.pending ]]; then
    record tailscale pass "package installed; enrollment gate documented"
  else
    record tailscale fail "installed but neither enrolled nor gated"
  fi
else
  record tailscale fail "package missing"
fi

if systemctl is-enabled --quiet atenea-worker-health.timer 2>/dev/null && systemctl is-active --quiet atenea-worker-health.timer 2>/dev/null; then
  record health_timer pass "enabled and active"
else
  record health_timer fail "not enabled/active"
fi

if [[ "${MODE}" == json ]]; then
  printf '{"ok":%s,"checks":[' "$([[ ${FAILURES} -eq 0 ]] && echo true || echo false)"
  for index in "${!NAMES[@]}"; do
    (( index == 0 )) || printf ','
    jq -cn --arg name "${NAMES[index]}" --arg state "${STATES[index]}" --arg detail "${DETAILS[index]}" '{name:$name,state:$state,detail:$detail}'
  done
  printf ']}\n'
else
  for index in "${!NAMES[@]}"; do
    printf '%-18s %-4s %s\n' "${NAMES[index]}" "${STATES[index]}" "${DETAILS[index]}"
  done
fi

if ${STRICT} && (( FAILURES > 0 )); then
  exit 1
fi
exit 0
