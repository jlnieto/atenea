#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$REPO_DIR"

docker compose -f docker-compose.dev.yml up -d db codex-app-server
docker compose -f docker-compose.dev.yml run --rm atenea-dev /bin/bash
