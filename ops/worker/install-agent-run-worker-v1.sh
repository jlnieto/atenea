#!/usr/bin/env bash
set -euo pipefail

ACTION="${1:-}"
CONTROL_PLANE_IP="${ATENEA_CONTROL_PLANE_TAILSCALE_IP:-}"
WORKER_ID="${ATENEA_AGENT_RUN_WORKER_ID:-ax42-01}"
PORT="${ATENEA_AGENT_RUN_WORKER_PORT:-8787}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE="atenea-agent-run-worker-v1.service"
PROGRAM="/usr/local/libexec/atenea/agent-run-worker-v1.py"
ENV_FILE="/etc/atenea-worker/agent-run-worker-v1.env"
TOKEN_FILE="/etc/atenea-worker/agent-run-worker-v1.token"
STATE_DIR="/srv/atenea/worker/agent-runs-v1"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_root() {
  [[ "$(id -u)" -eq 0 ]] || fail "run this action as root"
}

tailscale_ipv4() {
  ip -4 -o address show dev tailscale0 scope global \
    | awk 'NR == 1 { split($4, value, "/"); print value[1] }'
}

validate_inputs() {
  [[ "$PORT" =~ ^[0-9]+$ ]] && ((PORT >= 1024 && PORT <= 65535)) \
    || fail "worker port must be an unprivileged TCP port"
  [[ "$WORKER_ID" =~ ^[a-zA-Z0-9._-]{1,80}$ ]] || fail "worker id is invalid"
  [[ -f "$SCRIPT_DIR/agent-run-worker-v1.py" ]] || fail "worker program is missing"
  [[ -f "$SCRIPT_DIR/templates/$SERVICE" ]] || fail "systemd template is missing"
}

plan() {
  validate_inputs
  local bind
  bind="$(tailscale_ipv4)"
  [[ -n "$bind" ]] || fail "tailscale0 has no global IPv4 address"
  jq -n \
    --arg action apply \
    --arg worker_id "$WORKER_ID" \
    --arg bind "$bind" \
    --argjson port "$PORT" \
    --arg control_plane_ip "$CONTROL_PLANE_IP" \
    --arg protocol "agent-run-worker/v1" \
    --arg capability "synthetic-routing-v1" \
    '{
      action: $action,
      workerId: $worker_id,
      bind: $bind,
      port: $port,
      controlPlaneIp: (if $control_plane_ip == "" then null else $control_plane_ip end),
      protocol: $protocol,
      capabilities: [$capability],
      normalCapacity: 4,
      heavyCapacity: 2,
      tokenValueExposed: false,
      arbitraryExecution: false
    }'
}

apply_install() {
  require_root
  validate_inputs
  [[ "$CONTROL_PLANE_IP" =~ ^100\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$ ]] \
    || fail "ATENEA_CONTROL_PLANE_TAILSCALE_IP must be an exact tailnet IPv4 address"
  local bind
  bind="$(tailscale_ipv4)"
  [[ -n "$bind" ]] || fail "tailscale0 has no global IPv4 address"

  install -d -o root -g root -m 0755 /usr/local/libexec/atenea
  install -o root -g root -m 0755 "$SCRIPT_DIR/agent-run-worker-v1.py" "$PROGRAM"
  install -d -o root -g atenea -m 0750 /etc/atenea-worker
  install -d -o atenea-worker -g atenea -m 0700 "$STATE_DIR"
  if [[ ! -e "$TOKEN_FILE" ]]; then
    umask 0077
    openssl rand -hex 32 >"$TOKEN_FILE"
  fi
  chown root:atenea "$TOKEN_FILE"
  chmod 0640 "$TOKEN_FILE"

  {
    printf 'ATENEA_WORKER_BIND=%s\n' "$bind"
    printf 'ATENEA_WORKER_PORT=%s\n' "$PORT"
    printf 'ATENEA_WORKER_ID=%s\n' "$WORKER_ID"
  } >"$ENV_FILE"
  chown root:root "$ENV_FILE"
  chmod 0644 "$ENV_FILE"

  install -o root -g root -m 0644 "$SCRIPT_DIR/templates/$SERVICE" "/etc/systemd/system/$SERVICE"
  systemctl daemon-reload
  ufw allow in on tailscale0 proto tcp from "$CONTROL_PLANE_IP" to any port "$PORT" \
    comment 'atenea-agent-run-worker-v1' >/dev/null
  systemctl enable "$SERVICE"
  systemctl restart "$SERVICE"
  verify
}

verify() {
  require_root
  local bind
  bind="$(tailscale_ipv4)"
  systemctl is-enabled "$SERVICE"
  local ready=false
  for _attempt in $(seq 1 60); do
    if systemctl is-active --quiet "$SERVICE" \
        && ss -H -lntp "sport = :$PORT" | grep -F "$bind:$PORT" >/dev/null; then
      ready=true
      break
    fi
    sleep 0.25
  done
  [[ "$ready" == true ]] || fail "worker did not become ready within 15 seconds"
  systemctl is-active "$SERVICE"
  systemd-analyze security "$SERVICE" --no-pager >/dev/null
  ! ss -H -lntp "sport = :$PORT" | grep -Eq '(^|[[:space:]])(0\.0\.0\.0|\[::\]):' \
    || fail "worker has a wildcard listener"
  [[ "$(stat -c '%a:%U:%G' "$TOKEN_FILE")" == "640:root:atenea" ]] \
    || fail "token file ownership or mode is invalid"
  [[ "$(stat -c '%a:%U:%G' "$STATE_DIR")" == "700:atenea-worker:atenea" ]] \
    || fail "state directory ownership or mode is invalid"
  printf '%s\n' 'agent-run-worker-v1 verification passed'
}

disable_endpoint() {
  require_root
  systemctl disable --now "$SERVICE"
}

enable_endpoint() {
  require_root
  systemctl enable --now "$SERVICE"
  verify
}

case "$ACTION" in
  plan) plan ;;
  apply) apply_install ;;
  verify) verify ;;
  disable) disable_endpoint ;;
  enable) enable_endpoint ;;
  *) fail "usage: $0 plan|apply|verify|disable|enable" ;;
esac
