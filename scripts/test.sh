#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
source "$SCRIPT_DIR/lib/compose.sh"

cd "$REPO_DIR"

# A caller-owned, ephemeral test runtime may supply its own isolated database.
# This never starts, recreates or mounts a permanent DEV/PROD runtime.
case "${ATENEA_TEST_RUNTIME:-compose}" in
  isolated)
    [[ "${SPRING_DATASOURCE_URL:-}" =~ ^jdbc:postgresql://127\.0\.0\.1:[0-9]{1,5}/atenea_test$ ]] \
      || { echo 'Isolated tests require a loopback atenea_test database' >&2; exit 2; }
    [[ "${SPRING_DATASOURCE_USERNAME:-}" == atenea && "${SPRING_DATASOURCE_PASSWORD:-}" == atenea ]] \
      || { echo 'Isolated tests require synthetic test credentials' >&2; exit 2; }
    [[ -x /usr/share/maven/bin/mvn && -d /work/m2 ]] \
      || { echo 'Isolated tests require the pinned Maven runtime and offline cache' >&2; exit 2; }
    exec /usr/share/maven/bin/mvn -B --offline -Dmaven.repo.local=/work/m2 test "$@"
    ;;
  compose) ;;
  *) echo 'Unsupported test runtime' >&2; exit 2 ;;
esac

compose -f docker-compose.dev.yml up -d --build db-test codex-app-server

compose -f docker-compose.dev.yml run --rm --no-deps \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://db-test:5432/atenea_test \
  -e SPRING_DATASOURCE_USERNAME=atenea \
  -e SPRING_DATASOURCE_PASSWORD=atenea \
  atenea-dev /bin/sh -lc 'umask 0002 && exec ./mvnw test "$@"' sh "$@"
