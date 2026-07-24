#!/usr/bin/env bash

set -euo pipefail

action="${1:-status}"
backend="http://127.0.0.1:18083"
fqdn="codex-worker-01.tailf11cbc.ts.net"

[[ "${EUID}" -eq 0 ]] || {
  echo "Run as root." >&2
  exit 77
}

case "$action" in
  enable)
    curl --fail --silent --max-time 5 \
      "${backend}/actuator/health" |
      jq -e '.status == "UP"' >/dev/null
    tailscale serve --bg --https=443 "$backend"
    tailscale serve status --json |
      jq -e --arg host "${fqdn}:443" \
        '.TCP["443"].HTTPS == true
         and .Web[$host].Handlers["/"].Proxy == "http://127.0.0.1:18083"' \
        >/dev/null
    echo "Beautips private preview: https://${fqdn}/admin/login"
    ;;
  disable)
    tailscale serve --https=443 off
    ;;
  status)
    tailscale serve status
    ;;
  *)
    echo "Usage: sudo $0 {enable|disable|status}" >&2
    exit 64
    ;;
esac
