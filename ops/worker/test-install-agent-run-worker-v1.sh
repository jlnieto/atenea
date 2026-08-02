#!/usr/bin/env bash

set -Eeuo pipefail
umask 0077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TEST_ROOT="$(mktemp -d /tmp/agent-run-worker-install.XXXXXX)"

cleanup() {
  case "${TEST_ROOT}" in
    /tmp/agent-run-worker-install.*)
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

source "${SCRIPT_DIR}/install-agent-run-worker-v1.sh"
require_root() { :; }
chown() { :; }

MODE_FIXTURE="${TEST_ROOT}/mode-fixture"
mkdir -p "${MODE_FIXTURE}"
chmod 2770 "${MODE_FIXTURE}"
install_exact_directory "$(id -un)" "$(id -gn)" 0750 "${MODE_FIXTURE}/release"
[[ "$(stat -c '%a' "${MODE_FIXTURE}/release")" == 750 ]] \
  || fail "exact directory mode retained a setgid parent bit"

[[ "$(sha256sum "${SCRIPT_DIR}/templates/atenea-agent-run-worker-v1.service" | cut -d' ' -f1)" \
    == "${SERVICE_TEMPLATE_SHA256}" ]] || fail "service template fingerprint is stale"
[[ "$(sha256sum "${SCRIPT_DIR}/codex-platform-instructions-v1.md" | cut -d' ' -f1)" \
    == "${PLATFORM_INSTRUCTIONS_SHA256}" ]] || fail "platform instruction fingerprint is stale"
[[ "$(sha256sum "${SCRIPT_DIR}/agent-run-worker-v1.py" | cut -d' ' -f1)" \
    == "${PROGRAM_SHA256}" ]] || fail "worker program fingerprint is stale"
[[ "$(sha256sum "${SCRIPT_DIR}/project-codex-runner-v1.py" | cut -d' ' -f1)" \
    == "${PROJECT_RUNNER_SHA256}" ]] || fail "project runner fingerprint is stale"
[[ "$(sha256sum "${SCRIPT_DIR}/beautips-project-codex-runner-v1.py" | cut -d' ' -f1)" \
    == "${BEAUTIPS_PROJECT_RUNNER_SHA256}" ]] \
  || fail "Beautips compatibility runner fingerprint is stale"
[[ "$(sha256sum "${SCRIPT_DIR}/templates/${MATERIALIZATION_SERVICE}" | cut -d' ' -f1)" \
    == "${MATERIALIZATION_SERVICE_TEMPLATE_SHA256}" ]] \
  || fail "materialization service fingerprint is stale"
grep -Fqx 'ExecStart=/usr/local/libexec/atenea/install-agent-run-worker-v1.sh prepare-materialization-root' \
  "${SCRIPT_DIR}/templates/${MATERIALIZATION_SERVICE}" \
  || fail "materialization preparation command is not exact"
grep -Fqx "Requires=${MATERIALIZATION_SERVICE}" \
  "${SCRIPT_DIR}/templates/${SERVICE}" \
  || fail "worker does not require exact materialization preparation"
grep -Fqx 'RemainAfterExit=yes' "${SCRIPT_DIR}/templates/${MATERIALIZATION_SERVICE}" \
  || fail "materialization preparation is not retained for the worker lifetime"

SERVICE_TEMPLATE="${SCRIPT_DIR}/templates/atenea-agent-run-worker-v1.service"
[[ "$(grep -Fxc 'ReadOnlyPaths=/srv/atenea/attachments-v1' "${SERVICE_TEMPLATE}")" -eq 1 ]] \
  || fail "service does not expose only the fixed retained root read-only"
[[ "$(grep -Fc '/run/atenea/codex-images' "${SERVICE_TEMPLATE}")" -eq 1 ]] \
  || fail "service materialization write boundary is not exact"
! grep -E '^ReadWritePaths=.*attachments-v1' "${SERVICE_TEMPLATE}" >/dev/null \
  || fail "service grants attachment write access"

SESSION_ID=11111111-1111-4111-8111-111111111111
WORKSPACE_IDENTITY="remote:ax42-01:work-session:${SESSION_ID}"
WORKTREE="${TEST_ROOT}/srv/atenea/workspaces/sessions/${SESSION_ID}/atenea"
PROJECT_MIRROR="${TEST_ROOT}/srv/atenea/repositories/atenea.git"
PROJECT_REF="refs/remotes/origin/${PROJECT_BRANCH}"
PROJECT_WORKSPACES_ROOT="${TEST_ROOT}/srv/atenea/workspaces/sessions"
PROJECT_CONFIG="${TEST_ROOT}/etc/atenea-worker/project-codex-v1.json"
mkdir -p "${WORKTREE}" "$(dirname -- "${PROJECT_MIRROR}")" "$(dirname -- "${PROJECT_CONFIG}")"

git init -q --bare "${PROJECT_MIRROR}"
git init -q -b "${PROJECT_BRANCH}" "${WORKTREE}"
git -C "${WORKTREE}" config user.name Test
git -C "${WORKTREE}" config user.email test@example.invalid
git -C "${WORKTREE}" remote add origin "${PROJECT_REPOSITORY}"
mkdir -p "${WORKTREE}/ops"
printf '{}\n' >"${WORKTREE}/ops/atenea-runtime.json"
PROJECT_MANIFEST_SHA256="$(sha256sum "${WORKTREE}/ops/atenea-runtime.json" | cut -d' ' -f1)"
printf 'base\n' >"${WORKTREE}/tracked.txt"
git -C "${WORKTREE}" add .
git -C "${WORKTREE}" commit -qm base
RETAINED_COMMIT="$(git -C "${WORKTREE}" rev-parse HEAD)"
git --git-dir="${PROJECT_MIRROR}" fetch -q "${WORKTREE}" \
  "${RETAINED_COMMIT}:refs/remotes/origin/${PROJECT_BRANCH}"
printf 'canonical\n' >>"${WORKTREE}/tracked.txt"
git -C "${WORKTREE}" add tracked.txt
git -C "${WORKTREE}" commit -qm canonical
CANONICAL_COMMIT="$(git -C "${WORKTREE}" rev-parse HEAD)"
git --git-dir="${PROJECT_MIRROR}" fetch -q "${WORKTREE}" \
  "+${CANONICAL_COMMIT}:refs/remotes/origin/${PROJECT_BRANCH}"
git -C "${WORKTREE}" reset -q "${RETAINED_COMMIT}"
printf 'draft\n' >>"${WORKTREE}/tracked.txt"
printf 'owned\n' >"${TEST_ROOT}/allocation"
mkdir -p "$(dirname -- "${WORKTREE}")"
cp "${TEST_ROOT}/allocation" "$(dirname -- "${WORKTREE}")/runtime-allocation-v1.json"

ATTACHMENT_ROOT="/srv/atenea/attachments-v1"
write_project_config false false '{}' "${CANONICAL_COMMIT}"
[[ "$(jq -r '.attachmentRoot' "${PROJECT_CONFIG}")" == "${ATTACHMENT_ROOT}" ]] \
  || fail "project configuration omitted the fixed attachment root"
if jq '.attachmentRoot = "/srv/foreign"' "${PROJECT_CONFIG}" >"${PROJECT_CONFIG}.foreign" \
    && mv "${PROJECT_CONFIG}.foreign" "${PROJECT_CONFIG}" \
    && ( verify_project_config_content ) >/dev/null 2>&1; then
  fail "foreign attachment root was accepted"
fi
write_project_config false false '{}' "${CANONICAL_COMMIT}"
verify_project_config_file_identity() { :; }
BEAUTIPS_PROJECT_RUNNER="${TEST_ROOT}/beautips-project-codex-runner-v1.py"
printf 'accepted predecessor\n' >"${BEAUTIPS_PROJECT_RUNNER}"
BEAUTIPS_PROJECT_RUNNER_PREDECESSOR_SHA256="$(
  sha256sum "${BEAUTIPS_PROJECT_RUNNER}" | cut -d' ' -f1
)"
BEAUTIPS_PROJECT_RUNNER_SHA256="${BEAUTIPS_PROJECT_RUNNER_PREDECESSOR_SHA256}"
verify_beautips_project_runner_file_identity() { :; }
verify_beautips_project_runner_upgrade
printf 'foreign predecessor\n' >"${BEAUTIPS_PROJECT_RUNNER}"
if ( verify_beautips_project_runner_upgrade ) >/dev/null 2>&1; then
  fail "foreign Beautips compatibility runner was accepted"
fi
PRESERVED_CONFIG_SHA256="$(sha256sum "${PROJECT_CONFIG}" | cut -d' ' -f1)"
PREFLIGHT_SHA256="$(project_config_install_preflight)"
[[ "${PREFLIGHT_SHA256}" == "${PRESERVED_CONFIG_SHA256}" ]] \
  || fail "installer preflight did not retain the existing configuration identity"
project_config_install_finalize "${PREFLIGHT_SHA256}"
[[ "$(sha256sum "${PROJECT_CONFIG}" | cut -d' ' -f1)" == "${PRESERVED_CONFIG_SHA256}" ]] \
  || fail "installer finalize rewrote the existing configuration"

jq 'del(.attachmentRoot)' "${PROJECT_CONFIG}" >"${PROJECT_CONFIG}.legacy"
mv "${PROJECT_CONFIG}.legacy" "${PROJECT_CONFIG}"
LEGACY_CONFIG_SHA256="$(sha256sum "${PROJECT_CONFIG}" | cut -d' ' -f1)"
LEGACY_PREFLIGHT_SHA256="$(project_config_install_preflight)"
[[ "${LEGACY_PREFLIGHT_SHA256}" == "${LEGACY_CONFIG_SHA256}" ]] \
  || fail "installer preflight did not retain the exact legacy configuration"
project_config_install_finalize "${LEGACY_PREFLIGHT_SHA256}"
[[ "$(sha256sum "${PROJECT_CONFIG}" | cut -d' ' -f1)" == "${LEGACY_CONFIG_SHA256}" ]] \
  || fail "installer finalize rewrote the exact legacy configuration"

if jq '.foreignAuthority = true' "${PROJECT_CONFIG}" >"${PROJECT_CONFIG}.ambiguous" \
    && mv "${PROJECT_CONFIG}.ambiguous" "${PROJECT_CONFIG}" \
    && ( project_config_install_preflight ) >/dev/null 2>&1; then
  fail "installer preflight accepted ambiguous existing configuration"
fi
write_project_config false false '{}' "${CANONICAL_COMMIT}"
mv "${PROJECT_CONFIG}" "${PROJECT_CONFIG}.retained"
[[ -z "$(project_config_install_preflight)" ]] \
  || fail "installer preflight invented identity for an absent configuration"
project_config_install_finalize ""
jq -e '.selectionEnabled == false and .executionEnabled == false and (.workspaces | length) == 0' \
  "${PROJECT_CONFIG}" >/dev/null || fail "installer did not initialize a new configuration disabled"
rm -f "${PROJECT_CONFIG}.retained"

if jq '.attachmentRoots = [.attachmentRoot]' "${PROJECT_CONFIG}" >"${PROJECT_CONFIG}.ambiguous" \
    && mv "${PROJECT_CONFIG}.ambiguous" "${PROJECT_CONFIG}" \
    && ( verify_project_config_content ) >/dev/null 2>&1; then
  fail "ambiguous attachment root authority was accepted"
fi
write_project_config false false '{}' "${CANONICAL_COMMIT}"
SUDOERS_FILE="${TEST_ROOT}/project-runner.sudoers"
printf '%s\n' \
  "atenea-worker ALL=(root) NOPASSWD: ${PROJECT_RUNNER} --config ${PROJECT_CONFIG}" \
  "atenea-worker ALL=(root) NOPASSWD: ${PROJECT_RUNNER} --config ${PROJECT_CONFIG} --reconcile-materializations" \
  >"${SUDOERS_FILE}"
verify_project_runner_sudoers
printf '%s\n' \
  "atenea-worker ALL=(root) NOPASSWD: ${PROJECT_RUNNER} --config ${PROJECT_CONFIG} *" \
  >>"${SUDOERS_FILE}"
if ( verify_project_runner_sudoers ) >/dev/null 2>&1; then
  fail "broad project runner sudo authority was accepted"
fi
BEFORE="$(git -C "${WORKTREE}" status --porcelain=v1 --untracked-files=all)"
project_retained_draft_register "${SESSION_ID}" "${WORKSPACE_IDENTITY}" "${RETAINED_COMMIT}"
project_retained_draft_register "${SESSION_ID}" "${WORKSPACE_IDENTITY}" "${RETAINED_COMMIT}"
verify_project_config_content
AFTER="$(git -C "${WORKTREE}" status --porcelain=v1 --untracked-files=all)"

[[ "${BEFORE}" == "${AFTER}" ]] || fail "retained draft changed"
jq -e \
  --arg identity "${WORKSPACE_IDENTITY}" \
  --arg retained "${RETAINED_COMMIT}" \
  --arg canonical "${CANONICAL_COMMIT}" \
  '.selectionEnabled == true and
   .executionEnabled == false and
   .commit == $canonical and
   (.workspaces | keys) == [$identity] and
   .workspaces[$identity].canonicalCommit == $retained' \
  "${PROJECT_CONFIG}" >/dev/null || fail "retained registration is not exact"

if ( project_retained_draft_register \
    "${SESSION_ID}" "${WORKSPACE_IDENTITY}" "${CANONICAL_COMMIT}" ) >/dev/null 2>&1; then
  fail "current commit was accepted as retained"
fi

CONTROL_PLANE_IP=100.64.0.10
ATTACHMENT_ROOT="${TEST_ROOT}/retained"
MATERIALIZATION_PARENT="${TEST_ROOT}/materialization-parent"
MATERIALIZATION_ROOT="${MATERIALIZATION_PARENT}/codex-images"
mkdir -p "${ATTACHMENT_ROOT}" "${MATERIALIZATION_ROOT}"
chmod 0700 "${ATTACHMENT_ROOT}"
chmod 0750 "${MATERIALIZATION_PARENT}"
chmod 0710 "${MATERIALIZATION_ROOT}"
if ( verify_attachment_root ) >/dev/null 2>&1; then
  fail "foreign-owned attachment root was accepted"
fi
if ( verify_materialization_parent ) >/dev/null 2>&1; then
  fail "foreign-owned materialization parent was accepted"
fi
if ( verify_materialization_root ) >/dev/null 2>&1; then
  fail "foreign-owned materialization root was accepted"
fi
AMBIGUOUS_TARGET="${TEST_ROOT}/ambiguous-target"
mkdir -p "${AMBIGUOUS_TARGET}"
MATERIALIZATION_ROOT="${TEST_ROOT}/ambiguous-link"
ln -s "${AMBIGUOUS_TARGET}" "${MATERIALIZATION_ROOT}"
if ( verify_materialization_root ) >/dev/null 2>&1; then
  fail "symlinked materialization root was accepted"
fi

MATERIALIZATION_PARENT="${TEST_ROOT}/prepared-parent"
MATERIALIZATION_ROOT="${MATERIALIZATION_PARENT}/codex-images"
INSTALL_CALLS=()
install_exact_directory() {
  INSTALL_CALLS+=("$1:$2:$3:$4")
  mkdir -p "$4"
  chmod "$3" "$4"
}
PREPARE_PARENT_CHECKS=0
PREPARE_ROOT_CHECKS=0
verify_materialization_parent() {
  [[ -d "${MATERIALIZATION_PARENT}" && ! -L "${MATERIALIZATION_PARENT}" ]] \
    || fail "prepared parent is absent"
  PREPARE_PARENT_CHECKS=$((PREPARE_PARENT_CHECKS + 1))
}
verify_materialization_root() {
  [[ -d "${MATERIALIZATION_ROOT}" && ! -L "${MATERIALIZATION_ROOT}" ]] \
    || fail "prepared root is absent"
  PREPARE_ROOT_CHECKS=$((PREPARE_ROOT_CHECKS + 1))
}
prepare_materialization_root
prepare_materialization_root
[[ "${#INSTALL_CALLS[@]}" -eq 2 \
    && "${INSTALL_CALLS[0]}" == "root:atenea:0750:${MATERIALIZATION_PARENT}" \
    && "${INSTALL_CALLS[1]}" == "root:atenea:0710:${MATERIALIZATION_ROOT}" ]] \
  || fail "materialization preparer created anything beyond the exact absent paths"
[[ "${PREPARE_PARENT_CHECKS}" -eq 3 && "${PREPARE_ROOT_CHECKS}" -eq 3 ]] \
  || fail "materialization preparer did not reverify existing exact paths"

MATERIALIZATION_PARENT="${TEST_ROOT}/materialization-parent"
MATERIALIZATION_ROOT="${MATERIALIZATION_PARENT}/codex-images"
printf 'retained-sentinel\n' >"${ATTACHMENT_ROOT}/sentinel"
printf 'parent-sentinel\n' >"${MATERIALIZATION_PARENT}/sentinel"
printf 'materialized-sentinel\n' >"${MATERIALIZATION_ROOT}/sentinel"
BEFORE_BOUNDARIES="$(sha256sum "${ATTACHMENT_ROOT}/sentinel" \
  "${MATERIALIZATION_PARENT}/sentinel" "${MATERIALIZATION_ROOT}/sentinel")"
BOUNDARY_CHECKS=0
verify_attachment_root() { BOUNDARY_CHECKS=$((BOUNDARY_CHECKS + 1)); }
verify_materialization_parent() { BOUNDARY_CHECKS=$((BOUNDARY_CHECKS + 1)); }
verify_materialization_root() { BOUNDARY_CHECKS=$((BOUNDARY_CHECKS + 1)); }
SYSTEMCTL_CALLS=()
systemctl() { SYSTEMCTL_CALLS+=("$*"); }
UFW_CALLS=()
ufw() {
  if [[ "${1:-}" == status ]]; then
    printf '%s\n' "${PORT}/tcp on tailscale0 ALLOW IN ${CONTROL_PLANE_IP}"
  else
    UFW_CALLS+=("$*")
  fi
}
rollback_endpoint
AFTER_BOUNDARIES="$(sha256sum "${ATTACHMENT_ROOT}/sentinel" \
  "${MATERIALIZATION_PARENT}/sentinel" "${MATERIALIZATION_ROOT}/sentinel")"
[[ "${BOUNDARY_CHECKS}" -eq 6 ]] || fail "rollback did not verify all boundaries before and after"
[[ "${#SYSTEMCTL_CALLS[@]}" -eq 2 \
    && "${SYSTEMCTL_CALLS[0]}" == "disable --now ${SERVICE}" \
    && "${SYSTEMCTL_CALLS[1]}" == "stop ${MATERIALIZATION_SERVICE}" ]] \
  || fail "rollback service scope is not exact"
[[ "${#UFW_CALLS[@]}" -eq 1 ]] || fail "rollback firewall scope is not exact"
[[ "${BEFORE_BOUNDARIES}" == "${AFTER_BOUNDARIES}" ]] \
  || fail "rollback changed retained or materialized boundary content"

printf 'agent-run worker installer, sandbox and rollback tests passed\n'
