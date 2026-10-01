#!/usr/bin/env bash
# CI-only ephemeral toolchain. PostgreSQL is the job-owned loopback service.
# No App DEV/PROD container or shared compose stack is started.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
[[ "${CI:-}" == true ]] || { echo 'This runtime is only for isolated CI' >&2; exit 2; }
mkdir -p "$REPO_DIR/target/ci-m2"
docker run --rm --network host \
  -v "$REPO_DIR:/workspace/repos/internal/atenea" \
  -v "$REPO_DIR/target/ci-m2:/work/m2" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -w /workspace/repos/internal/atenea \
  -e ATENEA_TEST_RUNTIME=isolated \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/atenea_test \
  -e SPRING_DATASOURCE_USERNAME=atenea -e SPRING_DATASOURCE_PASSWORD=atenea \
  -e SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=0 -e SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5 \
  -e ATENEA_WORKSPACE_ROOT=/workspace/repos \
  maven:3.9.11-eclipse-temurin-21 /bin/bash -euc '
    apt-get update -qq
    apt-get install -y -qq --no-install-recommends git openssh-client
    mvn -B -Dmaven.repo.local=/work/m2 test -DskipTests
    mvn -B -Dmaven.repo.local=/work/m2 dependency:get -Dartifact=org.apache.maven.surefire:surefire-junit-platform:3.5.4
    mvn -B -Dmaven.repo.local=/work/m2 dependency:get -Dartifact=org.junit.platform:junit-platform-launcher:1.11.4
    exec ./scripts/test.sh "$@"
  ' bash "$@"
