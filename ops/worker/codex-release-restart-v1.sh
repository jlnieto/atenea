#!/usr/bin/env bash
set -euo pipefail

[[ $# -eq 2 ]] || exit 2
operation_id="$1"
service="$2"
[[ "$operation_id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$ ]] || exit 2
[[ "$service" == "atenea-agent-run-worker-v1.service" ]] || exit 2

unit="atenea-codex-boundary-${operation_id//-/}"
load_state="$(systemctl show "${unit}.service" -p LoadState --value 2>/dev/null || true)"
[[ "$load_state" != "not-found" && -n "$load_state" ]] && exit 0

exec systemd-run --quiet --unit="$unit" --on-active=2s --property=RemainAfterExit=yes \
  /usr/bin/systemctl restart atenea-agent-run-worker-v1.service
