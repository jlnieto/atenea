#!/usr/bin/env bash
set -euo pipefail

ACTION="${1:-}"
CONTROL_PLANE_IP="${ATENEA_CONTROL_PLANE_TAILSCALE_IP:-}"
WORKER_ID="${ATENEA_AGENT_RUN_WORKER_ID:-ax42-01}"
PORT="${ATENEA_AGENT_RUN_WORKER_PORT:-8787}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICE="atenea-agent-run-worker-v1.service"
PROGRAM="/usr/local/libexec/atenea/agent-run-worker-v1.py"
PROJECT_RUNNER="/usr/local/libexec/atenea/project-codex-runner-v1.py"
ENV_FILE="/etc/atenea-worker/agent-run-worker-v1.env"
TOKEN_FILE="/etc/atenea-worker/agent-run-worker-v1.token"
PROJECT_CONFIG="/etc/atenea-worker/project-codex-v1.json"
SUDOERS_FILE="/etc/sudoers.d/atenea-project-codex-v1"
STATE_DIR="/srv/atenea/worker/agent-runs-v1"
PROJECT_REPOSITORY="https://github.com/jlnieto/atenea.git"
PROJECT_BRANCH="feature/actualizar-conversacion-en-web"
PROJECT_COMMIT="b605c8d5b063e7321edd60fec2265ec7ddb84ea9"
PROJECT_MANIFEST_SHA256="3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3"

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
  [[ -f "$SCRIPT_DIR/project-codex-runner-v1.py" ]] || fail "project runner is missing"
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
    --arg synthetic_capability "synthetic-routing-v1" \
    --arg project_capability "project-codex-v1" \
    '{
      action: $action,
      workerId: $worker_id,
      bind: $bind,
      port: $port,
      controlPlaneIp: (if $control_plane_ip == "" then null else $control_plane_ip end),
      protocol: $protocol,
      capabilities: [$synthetic_capability],
      availableDisabledCapabilities: [$project_capability],
      normalCapacity: 4,
      heavyCapacity: 2,
      tokenValueExposed: false,
      arbitraryExecution: false
    }'
}

write_project_config() {
  local enabled="$1"
  local workspaces_json="$2"
  local temporary
  temporary="$(mktemp /etc/atenea-worker/.project-codex-v1.XXXXXX)"
  jq -n \
    --arg schema_version project-codex-v1 \
    --argjson enabled "$enabled" \
    --arg project_id atenea \
    --arg repository "$PROJECT_REPOSITORY" \
    --arg branch "$PROJECT_BRANCH" \
    --arg commit "$PROJECT_COMMIT" \
    --arg manifest_sha256 "$PROJECT_MANIFEST_SHA256" \
    --arg runner "$PROJECT_RUNNER" \
    --argjson workspaces "$workspaces_json" \
    '{
      schemaVersion: $schema_version,
      enabled: $enabled,
      projectId: $project_id,
      repository: $repository,
      branch: $branch,
      commit: $commit,
      manifestSha256: $manifest_sha256,
      runner: $runner,
      workspaces: $workspaces
    }' >"$temporary"
  chown root:root "$temporary"
  chmod 0644 "$temporary"
  mv -f "$temporary" "$PROJECT_CONFIG"
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
  install -o root -g root -m 0755 "$SCRIPT_DIR/project-codex-runner-v1.py" "$PROJECT_RUNNER"
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
  write_project_config false '{}'
  {
    printf 'atenea-worker ALL=(root) NOPASSWD: %s --config %s\n' "$PROJECT_RUNNER" "$PROJECT_CONFIG"
  } >"$SUDOERS_FILE"
  chown root:root "$SUDOERS_FILE"
  chmod 0440 "$SUDOERS_FILE"
  visudo -cf "$SUDOERS_FILE" >/dev/null

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
  ! ss -H -lntp "sport = :$PORT" \
      | awk '{ print $4 }' \
      | grep -Eq '^(0\.0\.0\.0|\[::\]):' \
    || fail "worker has a wildcard listener"
  [[ "$(stat -c '%a:%U:%G' "$TOKEN_FILE")" == "640:root:atenea" ]] \
    || fail "token file ownership or mode is invalid"
  [[ "$(stat -c '%a:%U:%G' "$STATE_DIR")" == "700:atenea-worker:atenea" ]] \
    || fail "state directory ownership or mode is invalid"
  [[ "$(stat -c '%a:%U:%G' "$PROJECT_CONFIG")" == "644:root:root" ]] \
    || fail "project configuration ownership or mode is invalid"
  jq -e '
    .schemaVersion == "project-codex-v1" and
    .projectId == "atenea" and
    (.enabled | type == "boolean") and
    (.workspaces | type == "object")
  ' "$PROJECT_CONFIG" >/dev/null || fail "project configuration is invalid"
  visudo -cf "$SUDOERS_FILE" >/dev/null
  printf '%s\n' 'agent-run-worker-v1 verification passed'
}

project_register() {
  require_root
  [[ "$#" -eq 2 ]] || fail "project-register requires SESSION_ID and WORKSPACE_IDENTITY"
  local session_id="$1"
  local workspace_identity="$2"
  [[ "$session_id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] \
    || fail "session id is invalid"
  [[ "$workspace_identity" == "remote:ax42-01:work-session:${session_id}" ]] \
    || fail "workspace identity is not exact"
  local worktree="/srv/atenea/workspaces/sessions/${session_id}/atenea"
  local allocation="/srv/atenea/workspaces/sessions/${session_id}/runtime-allocation-v1.json"
  [[ -d "$worktree" && ! -L "$worktree" && -f "$allocation" ]] \
    || fail "persisted workspace ownership is absent"
  [[ "$(git -C "$worktree" remote get-url origin)" == "$PROJECT_REPOSITORY" ]] \
    || fail "workspace remote is foreign"
  git -C "$worktree" merge-base --is-ancestor "$PROJECT_COMMIT" HEAD \
    || fail "workspace does not descend from the pinned commit"
  [[ "$(sha256sum "$worktree/ops/atenea-runtime.json" | cut -d' ' -f1)" == "$PROJECT_MANIFEST_SHA256" ]] \
    || fail "workspace manifest is foreign"
  local allocation_sha workspaces
  allocation_sha="$(sha256sum "$allocation" | cut -d' ' -f1)"
  workspaces="$(jq \
    --arg identity "$workspace_identity" \
    --arg session_id "$session_id" \
    --arg worktree "$worktree" \
    --arg allocation_sha256 "$allocation_sha" \
    '.workspaces + {
      ($identity): {
        sessionId: $session_id,
        worktree: $worktree,
        allocationSha256: $allocation_sha256
      }
    }' "$PROJECT_CONFIG")"
  write_project_config false "$workspaces"
}

project_enable() {
  require_root
  local workspaces
  workspaces="$(jq -c '.workspaces' "$PROJECT_CONFIG")"
  [[ "$(jq 'length' <<<"$workspaces")" -eq 1 ]] || fail "exactly one persisted workspace must be registered"
  write_project_config true "$workspaces"
  systemctl try-restart "$SERVICE"
}

project_disable() {
  require_root
  local workspaces
  workspaces="$(jq -c '.workspaces' "$PROJECT_CONFIG")"
  write_project_config false "$workspaces"
  systemctl try-restart "$SERVICE"
}

project_unregister() {
  require_root
  [[ "$#" -eq 2 ]] || fail "project-unregister requires SESSION_ID and WORKSPACE_IDENTITY"
  local session_id="$1"
  local workspace_identity="$2"
  local matches
  matches="$(jq -r \
    --arg identity "$workspace_identity" \
    --arg session_id "$session_id" \
    '(.workspaces[$identity].sessionId // "") == $session_id' "$PROJECT_CONFIG")"
  [[ "$matches" == true ]] || fail "exact persisted workspace ownership does not match"
  local workspaces
  workspaces="$(jq -c --arg identity "$workspace_identity" 'del(.workspaces[$identity]) | .workspaces' "$PROJECT_CONFIG")"
  write_project_config false "$workspaces"
  systemctl try-restart "$SERVICE"
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
  if ufw status | grep -F "$PORT/tcp on tailscale0" | grep -Fq "$CONTROL_PLANE_IP"; then
    ufw --force delete allow in on tailscale0 proto tcp from "$CONTROL_PLANE_IP" \
      to any port "$PORT" comment 'atenea-agent-run-worker-v1' >/dev/null
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
  project-register) shift; project_register "$@" ;;
  project-enable) project_enable ;;
  project-disable) project_disable ;;
  project-unregister) shift; project_unregister "$@" ;;
  *) fail "usage: $0 plan|apply|verify|disable|rollback|enable|project-register|project-enable|project-disable|project-unregister" ;;
esac
