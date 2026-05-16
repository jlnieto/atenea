#!/usr/bin/env bash
set -euo pipefail

APACHE_UNIT="${1:-apache2}"
OPS_USER="${2:-atenea-ops}"
PUBLIC_KEY="ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIG802W1xCYSdgdhec5asOMYUWs/tKKQ7sXfuTOCrmmVw atenea-ops-prod"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "Run this installer as root or with sudo." >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required by the Atenea runbooks to emit safe JSON." >&2
  exit 1
fi

useradd -m -s /bin/bash "$OPS_USER" 2>/dev/null || true
install -d -m 700 -o "$OPS_USER" -g "$OPS_USER" "/home/$OPS_USER/.ssh"
printf '%s\n' "$PUBLIC_KEY" >"/home/$OPS_USER/.ssh/authorized_keys"
chown "$OPS_USER:$OPS_USER" "/home/$OPS_USER/.ssh/authorized_keys"
chmod 600 "/home/$OPS_USER/.ssh/authorized_keys"

cat >/usr/local/sbin/atenea-host-status <<'SCRIPT_EOF'
#!/usr/bin/env bash
set -euo pipefail

json_value() {
  python3 -c 'import json,sys; print(json.dumps(sys.stdin.read().rstrip("\n")))' <<<"${1:-}"
}

started_at="$(date -Is)"
host_name="$(hostname -f 2>/dev/null || hostname)"
read -r load1 load5 load15 _ < /proc/loadavg
uptime_seconds="$(awk '{printf "%d", $1}' /proc/uptime)"
mem_total_kb="$(awk '/MemTotal/ {print $2}' /proc/meminfo)"
mem_available_kb="$(awk '/MemAvailable/ {print $2}' /proc/meminfo)"
mem_available_percent="$(awk -v total="${mem_total_kb:-0}" -v available="${mem_available_kb:-0}" 'BEGIN { if (total <= 0) print 0; else printf "%.1f", available * 100 / total }')"
root_disk_used_percent="$(df -P / | awk 'NR==2 {gsub("%", "", $5); print $5}')"
finished_at="$(date -Is)"

status="OK"
if awk -v disk="${root_disk_used_percent:-0}" 'BEGIN { exit !(disk >= 90) }'; then
  status="WARNING"
fi
if awk -v mem="${mem_available_percent:-0}" 'BEGIN { exit !(mem < 5) }'; then
  status="WARNING"
fi

summary="Servidor ${host_name}: carga ${load1}, memoria disponible ${mem_available_percent}%, disco raíz ${root_disk_used_percent}% usado."

cat <<JSON
{
  "action": "HOST_STATUS",
  "host": $(json_value "$host_name"),
  "startedAt": $(json_value "$started_at"),
  "finishedAt": $(json_value "$finished_at"),
  "status": $(json_value "$status"),
  "summary": $(json_value "$summary"),
  "steps": [
    {
      "name": "collect_load",
      "status": "OK",
      "detail": $(json_value "Leído /proc/loadavg: 1m=${load1}, 5m=${load5}, 15m=${load15}.")
    },
    {
      "name": "collect_memory",
      "status": "OK",
      "detail": $(json_value "MemAvailable=${mem_available_kb:-0} KB de MemTotal=${mem_total_kb:-0} KB (${mem_available_percent}%).")
    },
    {
      "name": "collect_disk",
      "status": "$status",
      "detail": $(json_value "Uso de disco en /: ${root_disk_used_percent}%.")
    }
  ],
  "metrics": {
    "load1": $(json_value "$load1"),
    "load5": $(json_value "$load5"),
    "load15": $(json_value "$load15"),
    "uptimeSeconds": ${uptime_seconds:-0},
    "memTotalKb": ${mem_total_kb:-0},
    "memAvailableKb": ${mem_available_kb:-0},
    "memAvailablePercent": $(json_value "$mem_available_percent"),
    "rootDiskUsedPercent": ${root_disk_used_percent:-0}
  }
}
JSON
SCRIPT_EOF

cat >/usr/local/sbin/atenea-apache-status <<SCRIPT_EOF
#!/usr/bin/env bash
set -euo pipefail

UNIT="$APACHE_UNIT"

json_value() {
  python3 -c 'import json,sys; print(json.dumps(sys.stdin.read().rstrip("\\n")))' <<<"\${1:-}"
}

apache_pids() {
  { pgrep -x apache2 2>/dev/null || true; pgrep -x httpd 2>/dev/null || true; } | sort -n | uniq
}

count_lines() {
  awk 'NF {count++} END {print count + 0}'
}

ports_listening_detail() {
  if ! command -v ss >/dev/null 2>&1; then
    printf 'ss no está disponible para comprobar puertos.'
    return
  fi
  local listeners
  listeners="\$(ss -ltn 2>/dev/null | awk '\$4 ~ /:80$/ || \$4 ~ /:443$/ {print \$4}' | sort -u | paste -sd ', ' -)"
  if [[ -z "\$listeners" ]]; then
    printf 'No se ven listeners TCP en 80/443.'
  else
    printf 'Listeners TCP detectados: %s.' "\$listeners"
  fi
}

started_at="\$(date -Is)"
host_name="\$(hostname -f 2>/dev/null || hostname)"
active="unknown"
failed="false"
if command -v systemctl >/dev/null 2>&1; then
  active="\$(systemctl is-active "\$UNIT" 2>/dev/null || true)"
  if systemctl is-failed "\$UNIT" >/dev/null 2>&1; then
    failed="true"
  fi
fi

pids="\$(apache_pids)"
process_count="\$(printf '%s\n' "\$pids" | count_lines)"
configtest_output="apachectl no está disponible"
configtest_status="SKIPPED"
if command -v apachectl >/dev/null 2>&1; then
  if configtest_output="\$(apachectl configtest 2>&1)"; then
    configtest_status="OK"
  else
    configtest_status="ERROR"
  fi
fi
port_detail="\$(ports_listening_detail)"
finished_at="\$(date -Is)"

ok="false"
status="ERROR"
if [[ "\$active" == "active" && "\$failed" == "false" && "\$configtest_status" != "ERROR" ]]; then
  ok="true"
  status="OK"
fi

summary="Apache \${UNIT}: active=\${active}, failed=\${failed}, procesos=\${process_count}, configtest=\${configtest_status}."

cat <<JSON
{
  "action": "APACHE_STATUS",
  "host": \$(json_value "\$host_name"),
  "startedAt": \$(json_value "\$started_at"),
  "finishedAt": \$(json_value "\$finished_at"),
  "status": \$(json_value "\$status"),
  "summary": \$(json_value "\$summary"),
  "steps": [
    {
      "name": "check_systemd",
      "status": \$(json_value "\$status"),
      "detail": \$(json_value "systemctl is-active \${UNIT} = \${active}; is-failed=\${failed}.")
    },
    {
      "name": "count_apache_processes",
      "status": "OK",
      "detail": \$(json_value "Procesos apache2/httpd detectados: \${process_count}.")
    },
    {
      "name": "validate_configuration",
      "status": \$(json_value "\$configtest_status"),
      "detail": \$(json_value "\$configtest_output")
    },
    {
      "name": "check_listening_ports",
      "status": "OK",
      "detail": \$(json_value "\$port_detail")
    }
  ],
  "metrics": {
    "ok": \$ok,
    "unit": \$(json_value "\$UNIT"),
    "active": \$(json_value "\$active"),
    "failed": \$failed,
    "processCount": \${process_count:-0},
    "configtest": \$(json_value "\$configtest_status")
  }
}
JSON

[[ "\$ok" == "true" ]]
SCRIPT_EOF

cat >/usr/local/sbin/atenea-apache-recover <<SCRIPT_EOF
#!/usr/bin/env bash
set -euo pipefail

UNIT="$APACHE_UNIT"

json_value() {
  python3 -c 'import json,sys; print(json.dumps(sys.stdin.read().rstrip("\\n")))' <<<"\${1:-}"
}

apache_pids() {
  { pgrep -x apache2 2>/dev/null || true; pgrep -x httpd 2>/dev/null || true; } | sort -n | uniq
}

count_lines() {
  awk 'NF {count++} END {print count + 0}'
}

join_pids() {
  paste -sd ',' -
}

active_state() {
  if command -v systemctl >/dev/null 2>&1; then
    systemctl is-active "\$UNIT" 2>/dev/null || true
  else
    printf 'unknown'
  fi
}

configtest() {
  if command -v apachectl >/dev/null 2>&1; then
    apachectl configtest 2>&1
  else
    printf 'apachectl no está disponible'
  fi
}

ports_listening_detail() {
  if ! command -v ss >/dev/null 2>&1; then
    printf 'ss no está disponible para comprobar puertos.'
    return
  fi
  local listeners
  listeners="\$(ss -ltn 2>/dev/null | awk '\$4 ~ /:80$/ || \$4 ~ /:443$/ {print \$4}' | sort -u | paste -sd ', ' -)"
  if [[ -z "\$listeners" ]]; then
    printf 'No se ven listeners TCP en 80/443.'
  else
    printf 'Listeners TCP detectados: %s.' "\$listeners"
  fi
}

started_at="\$(date -Is)"
host_name="\$(hostname -f 2>/dev/null || hostname)"
active_before="\$(active_state)"
pids_before="\$(apache_pids)"
process_count_before="\$(printf '%s\n' "\$pids_before" | count_lines)"
pids_before_joined="\$(printf '%s\n' "\$pids_before" | join_pids)"

stop_status="OK"
stop_detail="systemctl no está disponible; se saltó stop \${UNIT}."
if command -v systemctl >/dev/null 2>&1; then
  if systemctl stop "\$UNIT"; then
    stop_detail="systemctl stop \${UNIT} ejecutado correctamente."
  else
    stop_status="WARNING"
    stop_detail="systemctl stop \${UNIT} devolvió error; se continúa con terminación controlada de procesos."
  fi
  sleep 3
fi

pids_after_stop="\$(apache_pids)"
leftover_after_stop="\$(printf '%s\n' "\$pids_after_stop" | count_lines)"
pids_after_stop_joined="\$(printf '%s\n' "\$pids_after_stop" | join_pids)"

term_status="OK"
term_detail="No quedaban procesos apache2/httpd tras parar \${UNIT}."
if [[ "\$leftover_after_stop" -gt 0 ]]; then
  if printf '%s\n' "\$pids_after_stop" | xargs -r kill -TERM; then
    term_detail="Enviado SIGTERM a \${leftover_after_stop} procesos restantes: \${pids_after_stop_joined}."
  else
    term_status="WARNING"
    term_detail="SIGTERM devolvió error para algún proceso restante: \${pids_after_stop_joined}."
  fi
  sleep 5
fi

pids_after_term="\$(apache_pids)"
leftover_after_term="\$(printf '%s\n' "\$pids_after_term" | count_lines)"
pids_after_term_joined="\$(printf '%s\n' "\$pids_after_term" | join_pids)"
kill_status="OK"
kill_detail="No hizo falta SIGKILL."
if [[ "\$leftover_after_term" -gt 0 ]]; then
  if printf '%s\n' "\$pids_after_term" | xargs -r kill -KILL; then
    kill_detail="Enviado SIGKILL a \${leftover_after_term} procesos que sobrevivieron a SIGTERM: \${pids_after_term_joined}."
  else
    kill_status="ERROR"
    kill_detail="SIGKILL devolvió error para algún proceso: \${pids_after_term_joined}."
  fi
  sleep 2
fi

start_status="OK"
start_detail="systemctl no está disponible; no se pudo arrancar \${UNIT} con systemd."
if command -v systemctl >/dev/null 2>&1; then
  systemctl reset-failed "\$UNIT" || true
  if systemctl start "\$UNIT"; then
    start_detail="systemctl start \${UNIT} ejecutado correctamente."
  else
    start_status="ERROR"
    start_detail="systemctl start \${UNIT} devolvió error."
  fi
fi

active_after="\$(active_state)"
pids_after="\$(apache_pids)"
process_count_after="\$(printf '%s\n' "\$pids_after" | count_lines)"
configtest_output="\$(configtest)"
configtest_status="OK"
if command -v apachectl >/dev/null 2>&1 && ! apachectl configtest >/dev/null 2>&1; then
  configtest_status="ERROR"
fi
port_detail="\$(ports_listening_detail)"
finished_at="\$(date -Is)"

leftover_killed="\$(( leftover_after_stop > process_count_after ? leftover_after_stop - process_count_after : leftover_after_stop ))"
status="ERROR"
ok="false"
if [[ "\$active_after" == "active" && "\$start_status" == "OK" && "\$configtest_status" == "OK" ]]; then
  status="OK"
  ok="true"
fi

summary="Apache \${UNIT}: estado inicial \${active_before} con \${process_count_before} procesos; \${leftover_after_stop} procesos seguían vivos tras stop; estado final \${active_after} con \${process_count_after} procesos; configtest=\${configtest_status}."

cat <<JSON
{
  "action": "APACHE_RECOVERY",
  "host": \$(json_value "\$host_name"),
  "startedAt": \$(json_value "\$started_at"),
  "finishedAt": \$(json_value "\$finished_at"),
  "status": \$(json_value "\$status"),
  "summary": \$(json_value "\$summary"),
  "steps": [
    {
      "name": "snapshot_before",
      "status": "OK",
      "detail": \$(json_value "Antes de intervenir: active=\${active_before}, procesos=\${process_count_before}\${pids_before_joined:+, pids=\${pids_before_joined}}.")
    },
    {
      "name": "stop_apache",
      "status": \$(json_value "\$stop_status"),
      "detail": \$(json_value "\$stop_detail")
    },
    {
      "name": "detect_leftover_processes",
      "status": "OK",
      "detail": \$(json_value "Tras parar \${UNIT} quedaban \${leftover_after_stop} procesos\${pids_after_stop_joined:+: \${pids_after_stop_joined}}.")
    },
    {
      "name": "terminate_leftover_processes",
      "status": \$(json_value "\$term_status"),
      "detail": \$(json_value "\$term_detail")
    },
    {
      "name": "force_kill_survivors",
      "status": \$(json_value "\$kill_status"),
      "detail": \$(json_value "\$kill_detail")
    },
    {
      "name": "start_apache",
      "status": \$(json_value "\$start_status"),
      "detail": \$(json_value "\$start_detail")
    },
    {
      "name": "verify_service",
      "status": \$(json_value "\$status"),
      "detail": \$(json_value "systemctl is-active \${UNIT} = \${active_after}; apachectl configtest = \${configtest_status}; \${port_detail}")
    }
  ],
  "metrics": {
    "ok": \$ok,
    "unit": \$(json_value "\$UNIT"),
    "activeBefore": \$(json_value "\$active_before"),
    "activeAfter": \$(json_value "\$active_after"),
    "apacheProcessesBefore": \${process_count_before:-0},
    "leftoverAfterStop": \${leftover_after_stop:-0},
    "leftoverAfterTerm": \${leftover_after_term:-0},
    "leftoverKilled": \${leftover_killed:-0},
    "apacheProcessesAfter": \${process_count_after:-0},
    "configtest": \$(json_value "\$configtest_status")
  }
}
JSON

[[ "\$ok" == "true" ]]
SCRIPT_EOF

chmod 755 /usr/local/sbin/atenea-host-status /usr/local/sbin/atenea-apache-status /usr/local/sbin/atenea-apache-recover

cat >/etc/sudoers.d/atenea-ops <<'SUDOERS_EOF'
atenea-ops ALL=(root) NOPASSWD: /usr/local/sbin/atenea-host-status, /usr/local/sbin/atenea-apache-status, /usr/local/sbin/atenea-apache-recover
SUDOERS_EOF
chmod 440 /etc/sudoers.d/atenea-ops
visudo -cf /etc/sudoers.d/atenea-ops >/dev/null

echo "Installed Atenea structured runbooks for ${APACHE_UNIT} and user ${OPS_USER}"
