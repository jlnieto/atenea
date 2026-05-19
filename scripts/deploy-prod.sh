#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
STACK_DIR="${ATENEA_PROD_STACK_DIR:-/srv/atenea/platform/stacks/prod}"
COMPOSE_FILE="${STACK_DIR}/docker-compose.platform.yml"
ENV_FILE="${STACK_DIR}/.env"
HEALTH_URL="${ATENEA_PROD_HEALTH_URL:-http://127.0.0.1:8081/actuator/health}"
SERVICE_NAME="${ATENEA_PROD_BACKEND_SERVICE:-atenea-backend-prod}"
source "$SCRIPT_DIR/lib/compose.sh"

cd "$REPO_DIR"

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "Prod compose file not found: $COMPOSE_FILE" >&2
  exit 1
fi

compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d --build "$SERVICE_NAME"

for _ in $(seq 1 60); do
  if curl -fsS "$HEALTH_URL" >/dev/null; then
    echo "Prod deploy OK: $HEALTH_URL"
    exit 0
  fi
  sleep 2
done

echo "Prod deploy did not become healthy: $HEALTH_URL" >&2
compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" logs --tail=120 "$SERVICE_NAME" >&2
exit 1
