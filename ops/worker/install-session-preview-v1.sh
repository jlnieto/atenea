#!/usr/bin/env bash
set -euo pipefail

ACTION="${1:-}"
CONTROL_PLANE_IP="${ATENEA_CONTROL_PLANE_TAILSCALE_IP:-}"
WORKER_ID="${ATENEA_PREVIEW_WORKER_ID:-ax42-01}"
CONTROL_PORT="${ATENEA_PREVIEW_CONTROL_PORT:-8789}"
INGRESS_START="${ATENEA_PREVIEW_INGRESS_START:-19000}"
INGRESS_END="${ATENEA_PREVIEW_INGRESS_END:-19031}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE="atenea-session-preview-v1.service"
PROGRAM="/usr/local/libexec/atenea/session-preview-worker-v1.py"
ENV_FILE="/etc/atenea-worker/session-preview-v1.env"
TOKEN_FILE="/etc/atenea-worker/session-preview-v1.token"
STATE_ROOT="/srv/atenea/worker/session-preview-v1"

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
  [[ "$CONTROL_PORT" =~ ^[0-9]+$ &&
      "$INGRESS_START" =~ ^[0-9]+$ &&
      "$INGRESS_END" =~ ^[0-9]+$ &&
      "$CONTROL_PORT" -ge 1024 &&
      "$CONTROL_PORT" -le 65535 &&
      "$INGRESS_START" -ge 1024 &&
      "$INGRESS_END" -le 65535 &&
      "$INGRESS_START" -le "$INGRESS_END" &&
      ("$CONTROL_PORT" -lt "$INGRESS_START" || "$CONTROL_PORT" -gt "$INGRESS_END") ]] \
    || fail "preview ports are invalid"
  [[ "$WORKER_ID" =~ ^[a-zA-Z0-9._-]{1,80}$ ]] || fail "worker id is invalid"
  [[ -f "$SCRIPT_DIR/session-preview-worker-v1.py" ]] || fail "preview program is missing"
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
    --argjson control_port "$CONTROL_PORT" \
    --argjson ingress_start "$INGRESS_START" \
    --argjson ingress_end "$INGRESS_END" \
    --arg control_plane_ip "$CONTROL_PLANE_IP" \
    '{
      action: $action,
      workerId: $worker_id,
      bind: $bind,
      controlPort: $control_port,
      ingressRange: [$ingress_start, $ingress_end],
      controlPlaneIp: (if $control_plane_ip == "" then null else $control_plane_ip end),
      protocol: "session-preview/v1",
      publicSharing: false,
      arbitraryUpstream: false,
      stateRoot: "/srv/atenea/worker/session-preview-v1",
      workspaceRoot: "/srv/atenea/workspaces",
      tokenValueExposed: false
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
  install -o root -g root -m 0755 "$SCRIPT_DIR/session-preview-worker-v1.py" "$PROGRAM"
  install -d -o root -g atenea -m 0750 /etc/atenea-worker
  install -d -o atenea-worker -g atenea -m 0700 "$STATE_ROOT"
  install -d -o atenea-worker -g atenea -m 0700 "$STATE_ROOT/previews"
  if [[ ! -e "$TOKEN_FILE" ]]; then
    umask 0077
    openssl rand -hex 32 >"$TOKEN_FILE"
  fi
  chown root:atenea "$TOKEN_FILE"
  chmod 0640 "$TOKEN_FILE"

  {
    printf 'ATENEA_PREVIEW_BIND=%s\n' "$bind"
    printf 'ATENEA_PREVIEW_CONTROL_PORT=%s\n' "$CONTROL_PORT"
    printf 'ATENEA_PREVIEW_WORKER_ID=%s\n' "$WORKER_ID"
    printf 'ATENEA_PREVIEW_INGRESS_START=%s\n' "$INGRESS_START"
    printf 'ATENEA_PREVIEW_INGRESS_END=%s\n' "$INGRESS_END"
  } >"$ENV_FILE"
  chown root:root "$ENV_FILE"
  chmod 0644 "$ENV_FILE"

  install -o root -g root -m 0644 \
    "$SCRIPT_DIR/templates/$SERVICE" "/etc/systemd/system/$SERVICE"
  systemctl daemon-reload
  ufw allow in on tailscale0 proto tcp from "$CONTROL_PLANE_IP" \
    to any port "$CONTROL_PORT" comment 'atenea-session-preview-control-v1' >/dev/null
  ufw allow in on tailscale0 proto tcp from 100.64.0.0/10 \
    to any port "$INGRESS_START:$INGRESS_END" comment 'atenea-session-preview-ingress-v1' >/dev/null
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
        && ss -H -lntp "sport = :$CONTROL_PORT" | grep -F "$bind:$CONTROL_PORT" >/dev/null; then
      ready=true
      break
    fi
    sleep 0.25
  done
  [[ "$ready" == true ]] || fail "preview service did not become ready within 15 seconds"
  systemctl is-active "$SERVICE"
  systemd-analyze security "$SERVICE" --no-pager >/dev/null
  ! ss -H -lntp "sport = :$CONTROL_PORT" \
      | awk '{ print $4 }' \
      | grep -Eq '^(0\.0\.0\.0|\[::\]):' \
    || fail "preview control has a wildcard listener"
  [[ "$(stat -c '%a:%U:%G' "$TOKEN_FILE")" == "640:root:atenea" ]] \
    || fail "token file ownership or mode is invalid"
  [[ "$(stat -c '%a:%U:%G' "$STATE_ROOT")" == "700:atenea-worker:atenea" ]] \
    || fail "preview state root ownership or mode is invalid"
  ufw status | grep -F "$CONTROL_PORT/tcp on tailscale0" | grep -F "$CONTROL_PLANE_IP" >/dev/null \
    || fail "preview control firewall rule is missing"
  ufw status | grep -F "$INGRESS_START:$INGRESS_END/tcp on tailscale0" \
    | grep -F "100.64.0.0/10" >/dev/null \
    || fail "preview ingress firewall rule is missing"
  printf '%s\n' 'session-preview-v1 verification passed'
}

disable_endpoint() {
  require_root
  systemctl disable --now "$SERVICE"
}

rollback_endpoint() {
  require_root
  [[ "$CONTROL_PLANE_IP" =~ ^100\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$ ]] \
    || fail "ATENEA_CONTROL_PLANE_TAILSCALE_IP must be an exact tailnet IPv4 address"
  systemctl disable --now "$SERVICE"
  if ufw status | grep -F "$CONTROL_PORT/tcp on tailscale0" | grep -Fq "$CONTROL_PLANE_IP"; then
    ufw --force delete allow in on tailscale0 proto tcp from "$CONTROL_PLANE_IP" \
      to any port "$CONTROL_PORT" comment 'atenea-session-preview-control-v1' >/dev/null
  fi
  if ufw status | grep -F "$INGRESS_START:$INGRESS_END/tcp on tailscale0" \
      | grep -Fq "100.64.0.0/10"; then
    ufw --force delete allow in on tailscale0 proto tcp from 100.64.0.0/10 \
      to any port "$INGRESS_START:$INGRESS_END" \
      comment 'atenea-session-preview-ingress-v1' >/dev/null
  fi
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
  rollback) rollback_endpoint ;;
  enable) enable_endpoint ;;
  *) fail "usage: $0 plan|apply|verify|disable|rollback|enable" ;;
esac
