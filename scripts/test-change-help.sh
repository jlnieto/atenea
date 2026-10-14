#!/usr/bin/env bash
# Focused, job-owned offline backend tests. Never starts App DEV or touches PROD.
set -euo pipefail
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
task_container_id=''
cleanup() {
    if [[ "$task_container_id" =~ ^[0-9a-f]{64}$ ]]; then
        docker rm --force "$task_container_id" >/dev/null
    fi
}
trap cleanup EXIT
task_container_id=$(docker create \
    --label com.atenea.local-test=change-help \
    --network none --user 1000:0 --cap-drop ALL --security-opt no-new-privileges \
    --read-only --cpus 2 --memory 4g --pids-limit 512 \
    --tmpfs /work:rw,nosuid,nodev,size=5g,uid=1000,gid=0,mode=0700 \
    --tmpfs /workspace:rw,nosuid,nodev,size=512m,uid=1000,gid=0,mode=0700 \
    --tmpfs /tmp:rw,nosuid,nodev,size=512m,uid=1000,gid=0,mode=0700 \
    --mount "type=bind,src=$REPO_DIR,dst=/source,readonly" \
    --mount type=bind,src=/usr/local/libexec/atenea/atenea-backend-test-v2.py,dst=/opt/atenea-backend-test-v2.py,readonly \
    --env ATENEA_TEST_RUNTIME=isolated \
    --env SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/atenea_test \
    --env SPRING_DATASOURCE_USERNAME=atenea --env SPRING_DATASOURCE_PASSWORD=atenea \
    --env SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE=0 --env SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5 \
    --env ATENEA_WORKSPACE_ROOT=/workspace/repos --env JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 \
    sha256:c16bb041d67864734eba5e2377cfd553d5a284b159454d1b669d467a91f375f9 \
    /bin/sleep infinity)
docker start "$task_container_id" >/dev/null
docker exec "$task_container_id" /usr/bin/python3 /opt/atenea-backend-test-v2.py --prepare
docker exec --workdir /work/repo "$task_container_id" /bin/bash ./scripts/test.sh \
    -Dspring.test.context.cache.maxSize=1 \
    "-Dtest=${1:-ChangeHelpGuideTest,ChangeHelpServiceTest,ChangeHelpContextServiceTest,ChangeHelpControllerTest,DeepSeekChangeHelpExplainerTest}"
