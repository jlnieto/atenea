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
VALIDATION_MEDIATOR="/usr/local/libexec/atenea/atenea-validation-v1.sh"
PLAYWRIGHT_VALIDATOR="/usr/local/libexec/atenea/atenea-playwright-validation-v1.sh"
PLAYWRIGHT_CHECK="/usr/local/libexec/atenea/atenea-playwright-validation-v1.js"
ROLE_MEDIATOR="/usr/local/libexec/atenea/atenea-multi-repository-v1.sh"
PLATFORM_INSTRUCTIONS="/usr/local/share/atenea/codex-platform-instructions-v1.md"
INSTALLER="/usr/local/libexec/atenea/install-agent-run-worker-v1.sh"
ENV_FILE="/etc/atenea-worker/agent-run-worker-v1.env"
TOKEN_FILE="/etc/atenea-worker/agent-run-worker-v1.token"
PROJECT_CONFIG="/etc/atenea-worker/project-codex-v1.json"
SUDOERS_FILE="/etc/sudoers.d/atenea-project-codex-v1"
STATE_DIR="/srv/atenea/worker/agent-runs-v1"
PROJECT_REPOSITORY="https://github.com/jlnieto/atenea.git"
PROJECT_BRANCH="feature/actualizar-conversacion-en-web"
PROJECT_MANIFEST_SHA256="3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3"
PROJECT_MIRROR="/srv/atenea/repositories/atenea.git"
PROJECT_REF="refs/remotes/origin/${PROJECT_BRANCH}"
PROJECT_WORKSPACES_ROOT="/srv/atenea/workspaces/sessions"
SERVICE_TEMPLATE_SHA256="10f6583d72ef919a532b35f139e1daa57640ea688a6be12cb14452ce3ba149b3"
PLATFORM_INSTRUCTIONS_SHA256="44c578a286eb50b35612be0b6c38d59a503e6fee1ecf6cd0339415af018cdf0d"

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
  [[ -f "$SCRIPT_DIR/atenea-validation-v1.sh" ]] || fail "validation mediator is missing"
  [[ -f "$SCRIPT_DIR/atenea-playwright-validation-v1.sh" ]] || fail "Playwright validator is missing"
  [[ -f "$SCRIPT_DIR/atenea-playwright-validation-v1.js" ]] || fail "Playwright check is missing"
  [[ -f "$SCRIPT_DIR/atenea-multi-repository-v1.sh" ]] || fail "repository role mediator is missing"
  [[ -f "$SCRIPT_DIR/codex-platform-instructions-v1.md" ]] || fail "platform instructions are missing"
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
  local selection_enabled="$1"
  local execution_enabled="$2"
  local workspaces_json="$3"
  local canonical_commit="$4"
  local temporary
  temporary="$(mktemp "$(dirname "$PROJECT_CONFIG")/.project-codex-v1.XXXXXX")"
  jq -n \
    --arg schema_version project-codex-v1 \
    --argjson selection_enabled "$selection_enabled" \
    --argjson execution_enabled "$execution_enabled" \
    --arg project_id atenea \
    --arg repository "$PROJECT_REPOSITORY" \
    --arg branch "$PROJECT_BRANCH" \
    --arg commit "$canonical_commit" \
    --arg manifest_sha256 "$PROJECT_MANIFEST_SHA256" \
    --arg runner "$PROJECT_RUNNER" \
    --argjson workspaces "$workspaces_json" \
    '{
      schemaVersion: $schema_version,
      selectionEnabled: $selection_enabled,
      executionEnabled: $execution_enabled,
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

observe_project_commit() {
  local commit
  commit="$(git --git-dir="$PROJECT_MIRROR" rev-parse --verify "${PROJECT_REF}^{commit}")" \
    || fail "canonical mirror ref is unavailable"
  [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fail "canonical mirror ref is ambiguous"
  printf '%s\n' "$commit"
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
  install -d -o root -g root -m 0755 /usr/local/share/atenea
  install -o root -g root -m 0755 "$SCRIPT_DIR/agent-run-worker-v1.py" "$PROGRAM"
  install -o root -g root -m 0755 "$SCRIPT_DIR/project-codex-runner-v1.py" "$PROJECT_RUNNER"
  install -o root -g root -m 0755 "$SCRIPT_DIR/atenea-validation-v1.sh" "$VALIDATION_MEDIATOR"
  install -o root -g root -m 0755 "$SCRIPT_DIR/atenea-playwright-validation-v1.sh" "$PLAYWRIGHT_VALIDATOR"
  install -o root -g root -m 0644 "$SCRIPT_DIR/atenea-playwright-validation-v1.js" "$PLAYWRIGHT_CHECK"
  install -o root -g root -m 0755 "$SCRIPT_DIR/atenea-multi-repository-v1.sh" "$ROLE_MEDIATOR"
  install -o root -g root -m 0644 \
    "$SCRIPT_DIR/codex-platform-instructions-v1.md" "$PLATFORM_INSTRUCTIONS"
  id atenea-program-role >/dev/null 2>&1 || useradd --system --home-dir /nonexistent --shell /usr/sbin/nologin atenea-program-role
  id atenea-worker-role >/dev/null 2>&1 || useradd --system --home-dir /nonexistent --shell /usr/sbin/nologin atenea-worker-role
  install -o root -g root -m 0755 "$SCRIPT_DIR/install-agent-run-worker-v1.sh" "$INSTALLER"
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
  write_project_config false false '{}' "$(observe_project_commit)"
  {
    printf 'atenea-worker ALL=(root) NOPASSWD: %s --config %s\n' "$PROJECT_RUNNER" "$PROJECT_CONFIG"
    printf 'atenea-worker ALL=(root) NOPASSWD: %s BACKEND_TEST *\n' "$VALIDATION_MEDIATOR"
    printf 'atenea-worker ALL=(root) NOPASSWD: %s WEB_BUILD *\n' "$VALIDATION_MEDIATOR"
    printf 'atenea-worker ALL=(root) NOPASSWD: %s ANDROID_BUILD *\n' "$VALIDATION_MEDIATOR"
    printf 'atenea-worker ALL=(root) NOPASSWD: %s ensure *\n' "$ROLE_MEDIATOR"
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
  [[ -f "/etc/systemd/system/$SERVICE" \
      && "$(sha256sum "/etc/systemd/system/$SERVICE" | cut -d' ' -f1)" \
        == "$SERVICE_TEMPLATE_SHA256" ]] \
    || fail "worker systemd unit differs from the reviewed template"
  [[ -f "$INSTALLER" && ! -L "$INSTALLER" \
      && "$(stat -c '%a:%U:%G' "$INSTALLER")" == "755:root:root" \
      && "$(sha256sum "$INSTALLER" | cut -d' ' -f1)" \
        == "$(sha256sum "$SCRIPT_DIR/install-agent-run-worker-v1.sh" | cut -d' ' -f1)" ]] \
    || fail "worker installer differs from the reviewed source"
  [[ -f "$ROLE_MEDIATOR" && ! -L "$ROLE_MEDIATOR" \
      && "$(stat -c '%a:%U:%G' "$ROLE_MEDIATOR")" == "755:root:root" \
      && "$(sha256sum "$ROLE_MEDIATOR" | cut -d' ' -f1)" \
        == "$(sha256sum "$SCRIPT_DIR/atenea-multi-repository-v1.sh" | cut -d' ' -f1)" ]] \
    || fail "repository role mediator differs from the reviewed source"
  [[ -f "$PLATFORM_INSTRUCTIONS" && ! -L "$PLATFORM_INSTRUCTIONS" \
      && "$(stat -c '%a:%U:%G' "$PLATFORM_INSTRUCTIONS")" == "644:root:root" \
      && "$(sha256sum "$PLATFORM_INSTRUCTIONS" | cut -d' ' -f1)" \
        == "$PLATFORM_INSTRUCTIONS_SHA256" ]] \
    || fail "platform instructions differ from the reviewed source"
  [[ "$(getent passwd atenea-program-role | cut -d: -f7)" == /usr/sbin/nologin ]] \
    || fail "programme role identity is unavailable or interactive"
  [[ "$(getent passwd atenea-worker-role | cut -d: -f7)" == /usr/sbin/nologin ]] \
    || fail "worker source role identity is unavailable or interactive"
  jq -e '. as $root |
    .schemaVersion == "project-codex-v1" and
    .projectId == "atenea" and
    (.commit | test("^[0-9a-f]{40}$")) and
    (.selectionEnabled | type == "boolean") and
    (.executionEnabled | type == "boolean") and
    (.workspaces | type == "object") and
    ([.workspaces[] | .canonicalCommit == $root.commit] | all)
  ' "$PROJECT_CONFIG" >/dev/null || fail "project configuration is invalid"
  visudo -cf "$SUDOERS_FILE" >/dev/null
  printf '%s\n' 'agent-run-worker-v1 verification passed'
}

project_retained_draft_register() {
  require_root
  [[ "$#" -eq 3 ]] ||
    fail "project-retained-draft-register requires SESSION_ID, WORKSPACE_IDENTITY and RETAINED_COMMIT"
  local session_id="$1"
  local workspace_identity="$2"
  local retained_commit="$3"
  [[ "$session_id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] \
    || fail "session id is invalid"
  [[ "$workspace_identity" == "remote:ax42-01:work-session:${session_id}" ]] \
    || fail "workspace identity is not exact"
  [[ "$retained_commit" =~ ^[0-9a-f]{40}$ ]] || fail "retained commit is invalid"

  local worktree="${PROJECT_WORKSPACES_ROOT}/${session_id}/atenea"
  local allocation="${PROJECT_WORKSPACES_ROOT}/${session_id}/runtime-allocation-v1.json"
  [[ -d "$worktree" && ! -L "$worktree" && -f "$allocation" && ! -L "$allocation" ]] \
    || fail "persisted retained workspace ownership is absent"
  [[ "$(git -c safe.directory="$worktree" -C "$worktree" remote get-url origin)" == "$PROJECT_REPOSITORY" ]] \
    || fail "retained workspace remote is foreign"
  [[ "$(git -c safe.directory="$worktree" -C "$worktree" rev-parse --verify 'HEAD^{commit}')" \
      == "$retained_commit" ]] || fail "retained workspace HEAD is conflicting"

  local canonical_commit
  canonical_commit="$(observe_project_commit)"
  [[ "$retained_commit" != "$canonical_commit" ]] || fail "retained workspace is not stale"
  git --git-dir="$PROJECT_MIRROR" merge-base --is-ancestor "$retained_commit" "$canonical_commit" \
    || fail "retained workspace is not an ancestor of canonical source"
  [[ -n "$(git -c safe.directory="$worktree" -C "$worktree" status --porcelain=v1 --untracked-files=all)" ]] \
    || fail "retained workspace has no draft to preserve"
  [[ "$(sha256sum "$worktree/ops/atenea-runtime.json" | cut -d' ' -f1)" == "$PROJECT_MANIFEST_SHA256" ]] \
    || fail "retained workspace manifest is foreign"

  local allocation_sha exact_record existing_count existing_exact workspaces
  allocation_sha="$(sha256sum "$allocation" | cut -d' ' -f1)"
  exact_record="$(jq -cn \
    --arg session_id "$session_id" \
    --arg worktree "$worktree" \
    --arg allocation_sha256 "$allocation_sha" \
    --arg canonical_commit "$retained_commit" \
    '{
      sessionId: $session_id,
      worktree: $worktree,
      allocationSha256: $allocation_sha256,
      canonicalCommit: $canonical_commit
    }')"
  existing_count="$(jq '.workspaces | length' "$PROJECT_CONFIG")"
  existing_exact="$(jq -c --arg identity "$workspace_identity" '.workspaces[$identity] // null' "$PROJECT_CONFIG")"
  if [[ "$existing_count" -ne 0 &&
        ! ("$existing_count" -eq 1 && "$existing_exact" == "$exact_record") ]]; then
    fail "another persisted Atenea workspace is registered"
  fi
  workspaces="$(jq -cn \
    --arg identity "$workspace_identity" \
    --argjson record "$exact_record" \
    '{($identity): $record}')"
  write_project_config true false "$workspaces" "$canonical_commit"
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
  local worktree="${PROJECT_WORKSPACES_ROOT}/${session_id}/atenea"
  local allocation="${PROJECT_WORKSPACES_ROOT}/${session_id}/runtime-allocation-v1.json"
  [[ -d "$worktree" && ! -L "$worktree" && -f "$allocation" ]] \
    || fail "persisted workspace ownership is absent"
  [[ "$(git -c safe.directory="$worktree" -C "$worktree" remote get-url origin)" == "$PROJECT_REPOSITORY" ]] \
    || fail "workspace remote is foreign"
  local canonical_commit
  canonical_commit="$(observe_project_commit)"
  [[ "$(git -c safe.directory="$worktree" -C "$worktree" rev-parse --verify 'HEAD^{commit}')" \
      == "$canonical_commit" ]] || fail "workspace HEAD is not the canonical commit"
  [[ -z "$(git -c safe.directory="$worktree" -C "$worktree" status --porcelain=v1 --untracked-files=all)" ]] \
    || fail "workspace is not clean"
  [[ "$(sha256sum "$worktree/ops/atenea-runtime.json" | cut -d' ' -f1)" == "$PROJECT_MANIFEST_SHA256" ]] \
    || fail "workspace manifest is foreign"
  local allocation_sha workspaces
  allocation_sha="$(sha256sum "$allocation" | cut -d' ' -f1)"
  workspaces="$(jq \
    --arg identity "$workspace_identity" \
    --arg session_id "$session_id" \
    --arg worktree "$worktree" \
    --arg allocation_sha256 "$allocation_sha" \
    --arg canonical_commit "$canonical_commit" \
    '.workspaces + {
      ($identity): {
        sessionId: $session_id,
        worktree: $worktree,
        allocationSha256: $allocation_sha256,
        canonicalCommit: $canonical_commit
      }
    }' "$PROJECT_CONFIG")"
  write_project_config true false "$workspaces" "$canonical_commit"
}

project_selection_enable() {
  require_root
  local workspaces
  workspaces="$(jq -c '.workspaces' "$PROJECT_CONFIG")"
  [[ "$(jq 'length' <<<"$workspaces")" -le 1 ]] || fail "at most one persisted workspace may be registered"
  write_project_config true false "$workspaces" "$(jq -r '.commit' "$PROJECT_CONFIG")"
  systemctl try-restart "$SERVICE"
}

project_enable() {
  require_root
  local workspaces
  workspaces="$(jq -c '.workspaces' "$PROJECT_CONFIG")"
  [[ "$(jq 'length' <<<"$workspaces")" -eq 1 ]] || fail "exactly one persisted workspace must be registered"
  write_project_config true true "$workspaces" "$(jq -r '.commit' "$PROJECT_CONFIG")"
  systemctl try-restart "$SERVICE"
}

project_activate() {
  require_root
  [[ "$#" -eq 2 ]] || fail "project-activate requires SESSION_ID and WORKSPACE_IDENTITY"
  project_register "$1" "$2"
  local workspaces
  workspaces="$(jq -c '.workspaces' "$PROJECT_CONFIG")"
  [[ "$(jq 'length' <<<"$workspaces")" -eq 1 ]] || fail "exactly one persisted workspace must be registered"
  # The worker reads this file for every request. Avoid restarting the service
  # from inside the workspace-ensure request that is currently serving activation.
  write_project_config true true "$workspaces" "$(jq -r '.commit' "$PROJECT_CONFIG")"
}

project_disable() {
  require_root
  local workspaces
  workspaces="$(jq -c '.workspaces' "$PROJECT_CONFIG")"
  write_project_config false false "$workspaces" "$(jq -r '.commit' "$PROJECT_CONFIG")"
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
  write_project_config false false "$workspaces" "$(jq -r '.commit' "$PROJECT_CONFIG")"
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

if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
  return 0
fi

case "$ACTION" in
  plan) plan ;;
  apply) apply_install ;;
  verify) verify ;;
  disable) disable_endpoint ;;
  rollback) rollback_endpoint ;;
  enable) enable_endpoint ;;
  project-register) shift; project_register "$@" ;;
  project-retained-draft-register) shift; project_retained_draft_register "$@" ;;
  project-activate) shift; project_activate "$@" ;;
  project-selection-enable) project_selection_enable ;;
  project-enable) project_enable ;;
  project-disable) project_disable ;;
  project-unregister) shift; project_unregister "$@" ;;
  *) fail "usage: $0 plan|apply|verify|disable|rollback|enable|project-register|project-retained-draft-register|project-activate|project-selection-enable|project-enable|project-disable|project-unregister" ;;
esac
