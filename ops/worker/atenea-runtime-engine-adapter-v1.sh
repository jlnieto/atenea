#!/usr/bin/env bash

set -Eeuo pipefail
umask 0077

ACTION="${1:-}"
[[ "$#" -gt 0 ]] && shift

ENGINE_LABEL='atenea-runtime-engine-v1'
EXPECTED_SESSION='c20f3cde-9a64-4c7b-a674-7b63f94ca475'
EXPECTED_RUNTIME='ws-c20f3cde9a644c7ba6747b63f94ca475'
EXPECTED_COMMIT='b605c8d5b063e7321edd60fec2265ec7ddb84ea9'
EXPECTED_TREE='7a661346cbe0cab61485e52593d4ddfc8a4068a8'
EXPECTED_ARCHIVE_SHA256='ef785418b977fcab10b3cc2451c2ed6a2f15c7a1ec659a3ed14f03ec1a5b1a76'
EXPECTED_MANIFEST_SHA256='3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3'
EXPECTED_COMPOSE_SHA256='2133646b9fe6227ca417d6d62c92a74306caaa46a2957cdee810d5d7b0e5bb9f'
POSTGRES_IMAGE='postgres:16@sha256:33f923b05f64ca54ac4401c01126a6b92afe839a0aa0a52bc5aeb5cc958e5f20'
CODEX_IMAGE='sha256:c081aaa9d40afa4d8b57297000fe9aff5635e52a94b2b87abf8626b128c55e2d'
CODEX_IMAGE_ID='sha256:c081aaa9d40afa4d8b57297000fe9aff5635e52a94b2b87abf8626b128c55e2d'
APP_IMAGE='maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e'
NODE_IMAGE='node:22.16.0-bookworm-slim@sha256:048ed02c5fd52e86fda6fbd2f6a76cf0d4492fd6c6fee9e2c463ed5108da0e34'

PLAN=''
JSON_MODE=false
DOCKER_HOST_VALUE=''
DOCKER_BIN='/usr/bin/docker'
DELIVERY_BASE="${ATENEA_RUNTIME_DELIVERY_BASE:-/tmp/atenea-runtime-delivery}"
TEST_MODE="${ATENEA_RUNTIME_ATENEA_ADAPTER_TEST_MODE:-0}"

fail() {
  printf '%s: %s\n' "$1" "$2" >&2
  exit 65
}

usage() {
  printf 'Usage: %s execute --plan <path> --docker-host <unix socket> [--json]\n' \
    "$0" >&2
  exit 64
}

argument() {
  local expected="$1"
  shift
  while [[ "$#" -gt 0 ]]; do
    if [[ "$1" == "${expected}" && "$#" -ge 2 ]]; then
      printf '%s\n' "$2"
      return
    fi
    shift
  done
  return 1
}

for command in curl find flock git install jq realpath runuser sha256sum stat tar timeout; do
  command -v "${command}" >/dev/null ||
    fail OPERATION_FAILED "A fixed Atenea adapter prerequisite is unavailable."
done

if [[ "${TEST_MODE}" == '1' ]]; then
  DOCKER_BIN="${ATENEA_RUNTIME_DOCKER_BIN:-}"
  [[ "${DOCKER_BIN}" == /tmp/* && -x "${DOCKER_BIN}" && ! -L "${DOCKER_BIN}" ]] ||
    fail RUNTIME_OWNERSHIP_CONFLICT "The synthetic Docker client is unsafe."
  [[ "${DELIVERY_BASE}" == /tmp/* && "${DELIVERY_BASE}" != *'..'* ]] ||
    fail RUNTIME_OWNERSHIP_CONFLICT "The synthetic delivery root is unsafe."
else
  [[ "${EUID}" -eq 0 ]] ||
    fail RUNTIME_OWNERSHIP_CONFLICT "The Atenea adapter requires the mediated root boundary."
  [[ -x "${DOCKER_BIN}" && ! -L "${DOCKER_BIN}" ]] ||
    fail TOOLCHAIN_UNAVAILABLE "The pinned Docker client is unavailable."
  [[ "${DELIVERY_BASE}" == '/tmp/atenea-runtime-delivery' ]] ||
    fail RUNTIME_OWNERSHIP_CONFLICT "The production delivery base is fixed."
fi

docker_cmd() {
  DOCKER_HOST="${DOCKER_HOST_VALUE}" timeout --foreground 600 "${DOCKER_BIN}" "$@"
}

docker_exec_cmd() {
  if [[ "${TEST_MODE}" == '1' ]]; then
    docker_cmd exec "$@"
  else
    DOCKER_HOST='unix:///run/user/1103/docker.sock' \
      timeout --foreground 600 "${DOCKER_BIN}" exec "$@"
  fi
}

rootlesskit_api() {
  local method="$1" path="$2"
  shift 2
  runuser -u "${SLOT_USER}" -- \
    curl --silent --show-error --fail \
      --request "${method}" \
      --unix-socket /run/user/1103/dockerd-rootless/api.sock \
      "$@" "http://rootlesskit/v1/${path}"
}

rootlesskit_ports() {
  rootlesskit_api GET ports |
    jq -c 'if . == null then [] elif type == "array" then . else error("invalid ports") end'
}

assert_rootlesskit_port_boundary() {
  rootlesskit_api GET info |
    jq -e '
      .apiVersion == "1.1.2" and
      .version == "3.0.2" and
      .stateDir == "/run/user/1103/dockerd-rootless" and
      .networkDriver.driver == "slirp4netns" and
      .portDriver.driver == "builtin" and
      (.portDriver.protos | index("tcp4")) != null
    ' >/dev/null ||
    fail TOOLCHAIN_UNAVAILABLE "The slot3 RootlessKit port boundary differs from the reviewed contract."
}

remove_owned_rootless_ports() {
  local state="${ENGINE_ROOT}/rootlesskit-ports-v1.json"
  [[ -e "${state}" || -L "${state}" ]] || return 0
  [[ -f "${state}" && ! -L "${state}" &&
      "$(stat -c %u:%g:%a "${state}")" == '0:0:600' ]] ||
    fail RUNTIME_OWNERSHIP_CONFLICT "The retained RootlessKit port state is unsafe."
  local current id status
  current="$(rootlesskit_ports)"
  while IFS= read -r status; do
    id="$(jq -r '.id' <<<"${status}")"
    [[ "${id}" =~ ^[0-9]+$ ]] ||
      fail RUNTIME_OWNERSHIP_CONFLICT "A retained RootlessKit port identity is invalid."
    if jq -e --argjson expected "${status}" \
        'any(.[]; . == $expected)' <<<"${current}" >/dev/null; then
      rootlesskit_api DELETE "ports/${id}" >/dev/null
    fi
  done < <(jq -c '.[]' "${state}")
  find "${state}" -maxdepth 0 -type f -delete
}

add_rootlesskit_ports() {
  assert_rootlesskit_port_boundary
  remove_owned_rootless_ports
  local current state_temporary failed=false
  current="$(rootlesskit_ports)"
  jq -e \
    --argjson db "${POSTGRES_PORT}" \
    --argjson codex "${CODEX_PORT}" \
    --argjson web "${WEB_PORT}" '
      all(.[]; (.spec.parentPort == $db or
        .spec.parentPort == $codex or .spec.parentPort == $web) | not)
    ' <<<"${current}" >/dev/null ||
    fail RUNTIME_OWNERSHIP_CONFLICT "An allocated loopback port already has a foreign RootlessKit mapping."

  state_temporary="$(mktemp "${ENGINE_ROOT}/.rootlesskit-ports-v1.XXXXXX")"
  local container parent child ip spec response
  while IFS=$'\t' read -r container parent child; do
    ip="$(
      docker_cmd inspect "${container}" |
        jq -er --arg network "${NETWORK}" '
          .[0].NetworkSettings.Networks[$network].IPAddress |
          select(test("^[0-9]+(\\.[0-9]+){3}$"))
        '
    )"
    spec="$(
      jq -cn \
        --argjson parent "${parent}" \
        --argjson child "${child}" \
        --arg ip "${ip}" '{
          proto: "tcp4",
          parentIP: "127.0.0.1",
          parentPort: $parent,
          childIP: $ip,
          childPort: $child
        }'
    )"
    if ! response="$(
      rootlesskit_api POST ports \
        --header 'Content-Type: application/json' \
        --data-binary "${spec}"
    )"; then
      failed=true
      break
    fi
    jq -e --argjson spec "${spec}" \
      '.id >= 0 and .spec == $spec' <<<"${response}" >/dev/null ||
      failed=true
    [[ "${failed}" == false ]] || break
    printf '%s\n' "${response}" >>"${state_temporary}"
  done <<EOF
${DB_CONTAINER}	${POSTGRES_PORT}	5432
${CODEX_CONTAINER}	${CODEX_PORT}	8092
${APP_CONTAINER}	${WEB_PORT}	8081
EOF

  if [[ "${failed}" == true ]]; then
    while IFS= read -r response; do
      rootlesskit_api DELETE "ports/$(jq -r '.id' <<<"${response}")" >/dev/null || true
    done <"${state_temporary}"
    find "${state_temporary}" -maxdepth 0 -type f -delete
    fail OPERATION_FAILED "The exact slot3 loopback publication failed."
  fi
  jq -s '.' "${state_temporary}" >"${ENGINE_ROOT}/rootlesskit-ports-v1.json"
  chmod 0600 "${ENGINE_ROOT}/rootlesskit-ports-v1.json"
  find "${state_temporary}" -maxdepth 0 -type f -delete
}

rootlesskit_ports_ready() {
  local state="${ENGINE_ROOT}/rootlesskit-ports-v1.json"
  [[ -f "${state}" && ! -L "${state}" ]] || return 1
  local expected current
  expected="$(jq -cS 'sort_by(.id)' "${state}")"
  current="$(
    rootlesskit_ports |
      jq -cS \
        --argjson db "${POSTGRES_PORT}" \
        --argjson codex "${CODEX_PORT}" \
        --argjson web "${WEB_PORT}" '
          [.[] | select(
            .spec.parentPort == $db or
            .spec.parentPort == $codex or
            .spec.parentPort == $web
          )] | sort_by(.id)
        '
  )"
  [[ "${current}" == "${expected}" ]]
}

assert_regular() {
  [[ -f "$1" && ! -L "$1" ]] ||
    fail RUNTIME_OWNERSHIP_CONFLICT "A required Atenea runtime input is missing or unsafe."
}

assert_sha() {
  [[ "$(sha256sum "$1" | cut -d' ' -f1)" == "$2" ]] ||
    fail MANIFEST_INVALID "An Atenea runtime input differs from its reviewed SHA-256."
}

label_json() {
  local service="$1"
  jq -cn \
    --arg engine "${ENGINE_LABEL}" \
    --arg session "${SESSION}" \
    --arg runtime "${RUNTIME}" \
    --arg service "${service}" '{
      "com.atenea.engine": $engine,
      "com.atenea.session": $session,
      "com.atenea.runtime": $runtime,
      "com.atenea.project": "atenea",
      "com.atenea.service": $service
    }'
}

container_exists() {
  docker_cmd container inspect "$1" >/dev/null 2>&1
}

container_owned() {
  local name="$1" service="$2"
  docker_cmd container inspect "${name}" |
    jq -e --argjson expected "$(label_json "${service}")" '
      .[0].Config.Labels as $actual |
      all($expected | to_entries[]; $actual[.key] == .value) and
      all($actual | keys[];
        (startswith("com.atenea.") | not) or
        . == "com.atenea.engine" or
        . == "com.atenea.session" or
        . == "com.atenea.runtime" or
        . == "com.atenea.project" or
        . == "com.atenea.service" or
        . == "com.atenea.image" or
        . == "com.atenea.codex.version" or
        . == "com.atenea.node.version" or
        . == "com.atenea.codex.auth-boundary")
    ' >/dev/null
}

assert_container_owned_or_absent() {
  local name="$1" service="$2"
  if container_exists "${name}"; then
    container_owned "${name}" "${service}" ||
      fail RUNTIME_OWNERSHIP_CONFLICT "An Atenea container has foreign or incomplete labels."
  fi
}

assert_network_owned_or_absent() {
  if docker_cmd network inspect "${NETWORK}" >/dev/null 2>&1; then
    docker_cmd network inspect "${NETWORK}" |
      jq -e --argjson expected "$(label_json runtime)" '
        .[0].Labels as $actual |
        all($expected | to_entries[]; $actual[.key] == .value) and
        all($actual | keys[]; startswith("com.atenea.") or
          startswith("com.docker.compose.")) and
        .[0].Internal == true
      ' >/dev/null ||
      fail RUNTIME_OWNERSHIP_CONFLICT "The Atenea network has foreign ownership or is not internal."
  fi
}

build_network_owned() {
  docker_cmd network inspect "${BUILD_NETWORK}" |
    jq -e --argjson expected "$(label_json build)" '
      .[0].Labels as $actual |
      all($expected | to_entries[]; $actual[.key] == .value) and
      all($actual | keys[]; startswith("com.atenea.")) and
      .[0].Internal == false
    ' >/dev/null
}

cleanup_build_database() {
  if container_exists "${BUILD_CONTAINER}"; then
    container_owned "${BUILD_CONTAINER}" build ||
      fail RUNTIME_OWNERSHIP_CONFLICT "The Atenea build container is foreign."
    docker_cmd rm -f "${BUILD_CONTAINER}" >/dev/null
  fi
  if container_exists "${BUILD_DB_CONTAINER}"; then
    container_owned "${BUILD_DB_CONTAINER}" build-db ||
      fail RUNTIME_OWNERSHIP_CONFLICT "The Atenea test database container is foreign."
    docker_cmd rm -f "${BUILD_DB_CONTAINER}" >/dev/null
  fi
  if docker_cmd network inspect "${BUILD_NETWORK}" >/dev/null 2>&1; then
    build_network_owned ||
      fail RUNTIME_OWNERSHIP_CONFLICT "The Atenea test network is foreign."
    docker_cmd network rm "${BUILD_NETWORK}" >/dev/null
  fi
}

start_build_database() {
  cleanup_build_database
  docker_cmd network create \
    --label "com.atenea.engine=${ENGINE_LABEL}" \
    --label "com.atenea.session=${SESSION}" \
    --label "com.atenea.runtime=${RUNTIME}" \
    --label com.atenea.project=atenea \
    --label com.atenea.service=build \
    "${BUILD_NETWORK}" >/dev/null
  build_network_owned ||
    fail RUNTIME_OWNERSHIP_CONFLICT "The Atenea test network was not created safely."

  docker_cmd run --detach \
    --name "${BUILD_DB_CONTAINER}" \
    --network "${BUILD_NETWORK}" \
    --read-only \
    --tmpfs /var/lib/postgresql/data:rw,nosuid,nodev,size=512m \
    --tmpfs /var/run/postgresql:rw,noexec,nosuid,nodev,size=16m \
    --tmpfs /tmp:rw,noexec,nosuid,nodev,size=16m \
    --cap-drop ALL \
    --cap-add CHOWN \
    --cap-add DAC_OVERRIDE \
    --cap-add SETGID \
    --cap-add SETUID \
    --security-opt no-new-privileges:true \
    --pids-limit 256 \
    --memory 1g \
    --cpus 1 \
    --restart no \
    --label "com.atenea.engine=${ENGINE_LABEL}" \
    --label "com.atenea.session=${SESSION}" \
    --label "com.atenea.runtime=${RUNTIME}" \
    --label com.atenea.project=atenea \
    --label com.atenea.service=build-db \
    --env POSTGRES_DB=atenea_test \
    --env POSTGRES_USER=atenea \
    --env POSTGRES_PASSWORD=atenea \
    --health-cmd 'pg_isready --host=127.0.0.1 --username=atenea --dbname=atenea_test' \
    --health-interval 2s \
    --health-timeout 2s \
    --health-retries 30 \
    "${POSTGRES_IMAGE}" >/dev/null
  container_owned "${BUILD_DB_CONTAINER}" build-db ||
    fail RUNTIME_OWNERSHIP_CONFLICT "The Atenea test database was not created safely."

  local healthy=false
  for unused in $(seq 1 60); do
    if [[ "$(docker_cmd inspect -f '{{.State.Health.Status}}' "${BUILD_DB_CONTAINER}")" == healthy ]]; then
      healthy=true
      break
    fi
    sleep 1
  done
  [[ "${healthy}" == true ]] ||
    fail HEALTH_CHECK_FAILED "The isolated Atenea test database did not become healthy."
}

assert_retained_volume() {
  docker_cmd volume inspect "${VOLUME}" |
    jq -e --arg name "${VOLUME}" --argjson expected "$(label_json db)" '
      length == 1 and .[0].Name == $name and .[0].Driver == "local" and
      .[0].Labels == $expected and .[0].Options == null
    ' >/dev/null ||
    fail RUNTIME_OWNERSHIP_CONFLICT "The retained PostgreSQL volume is absent or differs."
}

assert_image() {
  local image="$1" expected_id="$2"
  docker_cmd image inspect "${image}" |
    jq -e --arg id "${expected_id}" \
      'length == 1 and .[0].Id == $id and .[0].Architecture == "amd64" and .[0].Os == "linux"' \
      >/dev/null ||
    fail TOOLCHAIN_UNAVAILABLE "An exact Atenea runtime image is unavailable."
}

clear_owned_delivery() {
  if [[ -e "${DELIVERY}" || -L "${DELIVERY}" ]]; then
    [[ -d "${DELIVERY}" && ! -L "${DELIVERY}" &&
        -f "${DELIVERY}/.owner-v1" && ! -L "${DELIVERY}/.owner-v1" &&
        "$(sed -n '1p' "${DELIVERY}/.owner-v1")" == \
          "${SESSION} ${RUNTIME} ${EXPECTED_COMMIT} ${EXPECTED_TREE}" ]] ||
      fail RUNTIME_OWNERSHIP_CONFLICT "The delivery path is foreign or ambiguous."
    find "${DELIVERY}" -xdev -depth -delete
  fi
}

assert_delivery() {
  [[ -d "${DELIVERY}" && ! -L "${DELIVERY}" &&
      -f "${DELIVERY}/.owner-v1" && ! -L "${DELIVERY}/.owner-v1" &&
      "$(sed -n '1p' "${DELIVERY}/.owner-v1")" == \
        "${SESSION} ${RUNTIME} ${EXPECTED_COMMIT} ${EXPECTED_TREE}" &&
      -d "${SOURCE}" && ! -L "${SOURCE}" ]] ||
    fail RECONCILIATION_REQUIRED "The exact Atenea source delivery has not been built."
  assert_sha "${SOURCE}/ops/atenea-runtime.json" "${EXPECTED_MANIFEST_SHA256}"
  assert_sha "${SOURCE}/ops/worker/docker-compose.ax42.yml" "${EXPECTED_COMPOSE_SHA256}"
}

prepare_delivery() {
  for container in "${DB_CONTAINER}" "${CODEX_CONTAINER}" "${APP_CONTAINER}"; do
    container_exists "${container}" &&
      fail RECONCILIATION_REQUIRED "Stop the admitted runtime before replacing its source delivery."
  done
  install -d -o root -g root -m 0711 "${DELIVERY_BASE}"
  [[ -d "${DELIVERY_BASE}" && ! -L "${DELIVERY_BASE}" &&
      "$(stat -c %u:%g:%a "${DELIVERY_BASE}")" == '0:0:711' ]] ||
    fail RUNTIME_OWNERSHIP_CONFLICT "The delivery base is unsafe."
  clear_owned_delivery

  local temporary="${DELIVERY}.new.$$"
  local archive="${DELIVERY_BASE}/.${RUNTIME}.archive.$$"
  [[ ! -e "${temporary}" && ! -L "${temporary}" &&
      ! -e "${archive}" && ! -L "${archive}" ]] ||
    fail RUNTIME_OWNERSHIP_CONFLICT "A temporary delivery identity already exists."
  install -d -o "${SLOT_USER}" -g "${SLOT_USER}" -m 0700 "${temporary}"
  install -o atenea-worker -g atenea -m 0600 /dev/null "${archive}"
  runuser -u atenea-worker -- \
    git -C "${WORKTREE}" archive --format=tar --output="${archive}" "${EXPECTED_COMMIT}"
  assert_sha "${archive}" "${EXPECTED_ARCHIVE_SHA256}"
  chown "${SLOT_USER}:${SLOT_USER}" "${archive}"
  runuser -u "${SLOT_USER}" -- install -d -m 0700 \
    "${temporary}/source" "${temporary}/cache/codex" \
    "${temporary}/cache/maven" "${temporary}/cache/node" \
    "${temporary}/data/uploads" "${temporary}/secrets"
  runuser -u "${SLOT_USER}" -- tar -xf "${archive}" -C "${temporary}/source"
  find "${archive}" -maxdepth 0 -type f -delete

  install -o "${SLOT_USER}" -g "${SLOT_USER}" -m 0444 \
    "${SECRET_ROOT}/ATENEA_DEV_POSTGRES_PASSWORD" \
    "${temporary}/secrets/postgres-password"
  install -o "${SLOT_USER}" -g "${SLOT_USER}" -m 0400 \
    "${SECRET_ROOT}/ATENEA_DEV_JWT_SECRET" \
    "${temporary}/secrets/jwt-secret"
  printf '%s %s %s %s\n' \
    "${SESSION}" "${RUNTIME}" "${EXPECTED_COMMIT}" "${EXPECTED_TREE}" \
    >"${temporary}/.owner-v1"
  chown root:root "${temporary}/.owner-v1"
  chmod 0400 "${temporary}/.owner-v1"
  mv "${temporary}" "${DELIVERY}"
  assert_delivery
}

retain_container_inspect() {
  local name="$1" destination="$2"
  docker_cmd container inspect "${name}" |
    jq '.[0] | {
      name: .Name,
      image: .Config.Image,
      labels: .Config.Labels,
      user: .Config.User,
      state: {
        status: .State.Status,
        exitCode: .State.ExitCode,
        oomKilled: .State.OOMKilled,
        health: (.State.Health.Status // null)
      },
      hostConfig: {
        networkMode: .HostConfig.NetworkMode,
        portBindings: .HostConfig.PortBindings,
        readonlyRootfs: .HostConfig.ReadonlyRootfs,
        privileged: .HostConfig.Privileged,
        capAdd: .HostConfig.CapAdd,
        capDrop: .HostConfig.CapDrop,
        securityOpt: .HostConfig.SecurityOpt,
        pidsLimit: .HostConfig.PidsLimit,
        memory: .HostConfig.Memory,
        nanoCpus: .HostConfig.NanoCpus
      },
      mounts: [.Mounts[] | {
        type: .Type, name: .Name, destination: .Destination, rw: .RW
      }]
    }' >"${destination}"
  chmod 0640 "${destination}"
}

build_application() {
  trap cleanup_build_database EXIT
  prepare_delivery
  assert_image "${APP_IMAGE}" \
    'sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e'
  assert_image "${NODE_IMAGE}" \
    'sha256:048ed02c5fd52e86fda6fbd2f6a76cf0d4492fd6c6fee9e2c463ed5108da0e34'
  assert_image "${POSTGRES_IMAGE}" \
    'sha256:33f923b05f64ca54ac4401c01126a6b92afe839a0aa0a52bc5aeb5cc958e5f20'
  assert_container_owned_or_absent "${BUILD_CONTAINER}" build
  if container_exists "${BUILD_CONTAINER}"; then
    docker_cmd rm -f "${BUILD_CONTAINER}" >/dev/null
  fi

  install -d -m 0750 "${LOGS_PATH}"
  docker_cmd run --detach \
    --name "${BUILD_CONTAINER}" \
    --network bridge \
    --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,nodev,size=256m \
    --cap-drop ALL \
    --security-opt no-new-privileges:true \
    --pids-limit 1024 \
    --memory 2g \
    --cpus 2 \
    --restart no \
    --label "com.atenea.engine=${ENGINE_LABEL}" \
    --label "com.atenea.session=${SESSION}" \
    --label "com.atenea.runtime=${RUNTIME}" \
    --label com.atenea.project=atenea \
    --label com.atenea.service=build \
    --mount "type=bind,source=${SOURCE},target=/workspace/atenea" \
    --mount "type=bind,source=${DELIVERY}/cache/node,target=/workspace/cache/node" \
    --workdir /workspace/atenea/web \
    --env npm_config_cache=/workspace/cache/node \
    --entrypoint /bin/sh \
    "${NODE_IMAGE}" \
    -lc 'exec sh -lc "npm ci --prefer-offline --no-audit && npm run build"' \
    >/dev/null

  local completed=false
  for unused in $(seq 1 900); do
    if [[ "$(docker_cmd inspect -f '{{.State.Running}}' "${BUILD_CONTAINER}")" == false ]]; then
      completed=true
      break
    fi
    sleep 1
  done
  docker_cmd logs "${BUILD_CONTAINER}" >"${LOGS_PATH}/web-build.log" 2>&1 || true
  retain_container_inspect "${BUILD_CONTAINER}" "${LOGS_PATH}/web-build-container.json"
  [[ "${completed}" == true ]] ||
    fail OPERATION_FAILED "The Atenea web build exceeded its finite timeout."
  local exit_code
  exit_code="$(docker_cmd inspect -f '{{.State.ExitCode}}' "${BUILD_CONTAINER}")"
  docker_cmd rm "${BUILD_CONTAINER}" >/dev/null
  [[ "${exit_code}" == 0 ]] ||
    fail OPERATION_FAILED "The exact Atenea web build failed."

  start_build_database
  docker_cmd run --detach \
    --name "${BUILD_CONTAINER}" \
    --network "${BUILD_NETWORK}" \
    --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,nodev,size=256m \
    --cap-drop ALL \
    --security-opt no-new-privileges:true \
    --pids-limit 1024 \
    --memory 3g \
    --cpus 2 \
    --restart no \
    --label "com.atenea.engine=${ENGINE_LABEL}" \
    --label "com.atenea.session=${SESSION}" \
    --label "com.atenea.runtime=${RUNTIME}" \
    --label com.atenea.project=atenea \
    --label com.atenea.service=build \
    --mount "type=bind,source=${SOURCE},target=/workspace/atenea" \
    --mount "type=bind,source=${DELIVERY}/cache/maven,target=/workspace/cache/maven" \
    --workdir /workspace/atenea \
    --env HOME=/workspace/cache/maven \
    --env SPRING_DATASOURCE_URL=jdbc:postgresql://${BUILD_DB_CONTAINER}:5432/atenea_test \
    --env SPRING_DATASOURCE_USERNAME=atenea \
    --env SPRING_DATASOURCE_PASSWORD=atenea \
    --entrypoint /bin/sh \
    "${APP_IMAGE}" \
    -lc 'exec mvn -B -Dmaven.repo.local=/workspace/cache/maven/repository clean package' \
    >/dev/null

  completed=false
  for unused in $(seq 1 900); do
    if [[ "$(docker_cmd inspect -f '{{.State.Running}}' "${BUILD_CONTAINER}")" == false ]]; then
      completed=true
      break
    fi
    sleep 1
  done
  docker_cmd logs "${BUILD_CONTAINER}" >"${LOGS_PATH}/build.log" 2>&1 || true
  retain_container_inspect "${BUILD_CONTAINER}" "${LOGS_PATH}/build-container.json"
  [[ "${completed}" == true ]] ||
    fail OPERATION_FAILED "The Atenea application build exceeded its finite timeout."
  exit_code="$(docker_cmd inspect -f '{{.State.ExitCode}}' "${BUILD_CONTAINER}")"
  docker_cmd rm "${BUILD_CONTAINER}" >/dev/null
  cleanup_build_database
  trap - EXIT
  [[ "${exit_code}" == 0 ]] ||
    fail OPERATION_FAILED "The exact Atenea application build failed."

  mapfile -t jars < <(
    find "${SOURCE}/target" -maxdepth 1 -type f -name '*.jar' \
      ! -name '*.original' -printf '%f\n' | LC_ALL=C sort
  )
  [[ "${#jars[@]}" -eq 1 ]] ||
    fail OPERATION_FAILED "The Atenea build did not produce one unambiguous executable JAR."
  printf '%s\n' "${jars[0]}" >"${DELIVERY}/.application-jar-v1"
  chown root:root "${DELIVERY}/.application-jar-v1"
  chmod 0400 "${DELIVERY}/.application-jar-v1"
}

write_compose() {
  assert_delivery
  assert_regular "${DELIVERY}/.application-jar-v1"
  local jar
  jar="$(sed -n '1p' "${DELIVERY}/.application-jar-v1")"
  [[ "${jar}" =~ ^[A-Za-z0-9._-]+\.jar$ &&
      -f "${SOURCE}/target/${jar}" && ! -L "${SOURCE}/target/${jar}" ]] ||
    fail RECONCILIATION_REQUIRED "The admitted Atenea JAR is missing or ambiguous."

  jq -n \
    --arg postgresImage "${POSTGRES_IMAGE}" \
    --arg codexImage "${CODEX_IMAGE}" \
    --arg appImage "${APP_IMAGE}" \
    --arg dbContainer "${DB_CONTAINER}" \
    --arg codexContainer "${CODEX_CONTAINER}" \
    --arg appContainer "${APP_CONTAINER}" \
    --arg network "${NETWORK}" \
    --arg volume "${VOLUME}" \
    --arg source "${SOURCE}" \
    --arg codexCache "${DELIVERY}/cache/codex" \
    --arg mavenCache "${DELIVERY}/cache/maven" \
    --arg nodeCache "${DELIVERY}/cache/node" \
    --arg uploads "${DELIVERY}/data/uploads" \
    --arg postgresSecret "${DELIVERY}/secrets/postgres-password" \
    --arg jwtSecret "${DELIVERY}/secrets/jwt-secret" \
    --arg jar "/workspace/atenea/target/${jar}" \
    --argjson dbLabels "$(label_json db)" \
    --argjson codexLabels "$(label_json codex-app-server)" \
    --argjson appLabels "$(label_json atenea-dev)" \
    --argjson runtimeLabels "$(label_json runtime)" '{
      services: {
        db: {
          image: $postgresImage,
          container_name: $dbContainer,
          user: "999:999",
          environment: {
            POSTGRES_DB: "atenea_ax42_synthetic_v1",
            POSTGRES_USER: "atenea_ax42_synthetic_v1",
            POSTGRES_PASSWORD_FILE: "/run/secrets/postgres-password"
          },
          command: ["postgres", "-c", "listen_addresses=*", "-c", "port=5432"],
          volumes: [
            {type: "volume", source: "db-data", target: "/var/lib/postgresql/data"},
            {type: "bind", source: $postgresSecret, target: "/run/secrets/postgres-password", read_only: true}
          ],
          networks: {runtime: {aliases: ["db"]}},
          healthcheck: {
            test: ["CMD-SHELL", "pg_isready --host=127.0.0.1 --port=5432 --username=atenea_ax42_synthetic_v1 --dbname=atenea_ax42_synthetic_v1"],
            interval: "5s", timeout: "3s", retries: 30, start_period: "10s"
          },
          read_only: true,
          tmpfs: [
            "/run/postgresql:rw,noexec,nosuid,nodev,size=16m,uid=999,gid=999,mode=3775",
            "/tmp:rw,noexec,nosuid,nodev,size=16m,uid=999,gid=999,mode=1777"
          ],
          cap_drop: ["ALL"],
          security_opt: ["no-new-privileges:true"],
          pids_limit: 256,
          restart: "no",
          labels: $dbLabels
        },
        "codex-app-server": {
          image: $codexImage,
          container_name: $codexContainer,
          user: "0:0",
          working_dir: "/workspace/atenea",
          environment: {
            HOME: "/workspace/cache/codex",
            ATENEA_CODEX_REQUIRED_AUTH_MODE: "disabled",
            ATENEA_CODEX_AUTH_STATUS_FILE: "/workspace/cache/codex/auth-status.json"
          },
          command: [
            "node", "/usr/local/lib/atenea/codex-loopback-proxy.mjs",
            "-c", "approval_policy=\"never\"",
            "-c", "sandbox_mode=\"workspace-write\""
          ],
          volumes: [
            {type: "bind", source: $source, target: "/workspace/atenea"},
            {type: "bind", source: $codexCache, target: "/workspace/cache/codex"}
          ],
          networks: ["runtime"],
          read_only: true,
          tmpfs: ["/tmp:rw,noexec,nosuid,nodev,size=64m"],
          cap_drop: ["ALL"],
          security_opt: ["no-new-privileges:true"],
          pids_limit: 512,
          restart: "no",
          labels: $codexLabels
        },
        "atenea-dev": {
          image: $appImage,
          container_name: $appContainer,
          working_dir: "/workspace/atenea",
          depends_on: {
            db: {condition: "service_healthy"},
            "codex-app-server": {condition: "service_started"}
          },
          environment: {
            SERVER_PORT: "8081",
            SPRING_CONFIG_IMPORT: "configtree:/run/secrets/",
            SPRING_DATASOURCE_URL: "jdbc:postgresql://db:5432/atenea_ax42_synthetic_v1",
            SPRING_DATASOURCE_USERNAME: "atenea_ax42_synthetic_v1",
            ATENEA_WORKSPACE_ROOT: "/workspace",
            ATENEA_CODEX_APP_SERVER_URL: "ws://codex-app-server:8092",
            ATENEA_CODEX_APP_SERVER_CWD: "/workspace/atenea",
            ATENEA_CODEX_REQUIRED_AUTH_MODE: "disabled",
            ATENEA_AUTH_BOOTSTRAP_ENABLED: "false",
            ATENEA_MOBILE_UPLOAD_ROOT: "/workspace/data/uploads",
            ATENEA_OPENAI_ENABLED: "false",
            ATENEA_OPENAI_COSTS_ENABLED: "false",
            ATENEA_OPENAI_API_BASE_URL: "http://127.0.0.1:9",
            ATENEA_OPENAI_COSTS_API_BASE_URL: "http://127.0.0.1:9",
            ATENEA_CORE_INTENT_ROUTER_ENABLED: "false",
            ATENEA_BRIEFING_ENABLED: "false",
            ATENEA_DEEPSEEK_COSTS_ENABLED: "false",
            ATENEA_DEEPSEEK_API_BASE_URL: "http://127.0.0.1:9",
            ATENEA_MOBILE_PUSH_ENABLED: "false",
            ATENEA_MOBILE_PUSH_FCM_TOKEN_URL: "http://127.0.0.1:9",
            ATENEA_MOBILE_PUSH_FCM_API_BASE_URL: "http://127.0.0.1:9",
            ATENEA_GITHUB_API_BASE_URL: "http://127.0.0.1:9",
            MAVEN_CONFIG: "/workspace/cache/maven",
            npm_config_cache: "/workspace/cache/node"
          },
          command: ["java", "-jar", $jar],
          volumes: [
            {type: "bind", source: $source, target: "/workspace/atenea"},
            {type: "bind", source: $mavenCache, target: "/workspace/cache/maven"},
            {type: "bind", source: $nodeCache, target: "/workspace/cache/node"},
            {type: "bind", source: $uploads, target: "/workspace/data/uploads"},
            {type: "bind", source: $postgresSecret, target: "/run/secrets/spring.datasource.password", read_only: true},
            {type: "bind", source: $jwtSecret, target: "/run/secrets/atenea.auth.jwt.secret", read_only: true}
          ],
          networks: ["runtime"],
          read_only: true,
          tmpfs: ["/tmp:rw,noexec,nosuid,nodev,size=64m"],
          cap_drop: ["ALL"],
          security_opt: ["no-new-privileges:true"],
          pids_limit: 1024,
          restart: "no",
          labels: $appLabels
        }
      },
      networks: {
        runtime: {
          name: $network,
          internal: true,
          labels: $runtimeLabels
        }
      },
      volumes: {
        "db-data": {
          external: true,
          name: $volume
        }
      }
    }' >"${COMPOSE_FILE}"
  chmod 0600 "${COMPOSE_FILE}"
}

start_runtime() {
  assert_retained_volume
  assert_image "${POSTGRES_IMAGE}" \
    'sha256:33f923b05f64ca54ac4401c01126a6b92afe839a0aa0a52bc5aeb5cc958e5f20'
  assert_image "${CODEX_IMAGE}" "${CODEX_IMAGE_ID}"
  assert_image "${APP_IMAGE}" \
    'sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e'
  assert_network_owned_or_absent
  assert_container_owned_or_absent "${DB_CONTAINER}" db
  assert_container_owned_or_absent "${CODEX_CONTAINER}" codex-app-server
  assert_container_owned_or_absent "${APP_CONTAINER}" atenea-dev
  write_compose
  docker_cmd compose \
    --project-name "${COMPOSE_PROJECT}" \
    --file "${COMPOSE_FILE}" \
    up --detach --no-build --pull never >/dev/null
  add_rootlesskit_ports
}

tcp_listener_ready() {
  local port="$1"
  timeout 3 bash -c 'exec 3<>"/dev/tcp/127.0.0.1/$1"' bash "${port}"
}

database_summary() {
  docker_exec_cmd --user 999:999 "${DB_CONTAINER}" \
    psql --no-psqlrc --tuples-only --no-align \
      --username atenea_ax42_synthetic_v1 \
      --dbname atenea_ax42_synthetic_v1 --command \
      "SELECT count(*),min(installed_rank),max(installed_rank),count(*) FILTER (WHERE success),count(*) FILTER (WHERE NOT success),max(version::integer) FROM flyway_schema_history;"
}

domain_counts() {
  docker_exec_cmd --interactive --user 999:999 "${DB_CONTAINER}" \
    psql --no-psqlrc --tuples-only --no-align \
      --username atenea_ax42_synthetic_v1 \
      --dbname atenea_ax42_synthetic_v1 <<'SQL'
SELECT 'agent_run,' || count(*) FROM agent_run
UNION ALL SELECT 'api_usage_record,' || count(*) FROM api_usage_record
UNION ALL SELECT 'core_command,' || count(*) FROM core_command
UNION ALL SELECT 'core_command_event,' || count(*) FROM core_command_event
UNION ALL SELECT 'core_operator_context,' || count(*) FROM core_operator_context
UNION ALL SELECT 'managed_host,' || count(*) FROM managed_host
UNION ALL SELECT 'managed_service,' || count(*) FROM managed_service
UNION ALL SELECT 'managed_website,' || count(*) FROM managed_website
UNION ALL SELECT 'mobile_push_notification_log,' || count(*) FROM mobile_push_notification_log
UNION ALL SELECT 'operations_action_run,' || count(*) FROM operations_action_run
UNION ALL SELECT 'operations_incident,' || count(*) FROM operations_incident
UNION ALL SELECT 'operator_account,' || count(*) FROM operator_account
UNION ALL SELECT 'operator_push_device,' || count(*) FROM operator_push_device
UNION ALL SELECT 'operator_refresh_token,' || count(*) FROM operator_refresh_token
UNION ALL SELECT 'operator_session_read_state,' || count(*) FROM operator_session_read_state
UNION ALL SELECT 'project,' || count(*) FROM project
UNION ALL SELECT 'project_database_refresh_run,' || count(*) FROM project_database_refresh_run
UNION ALL SELECT 'project_verification_run,' || count(*) FROM project_verification_run
UNION ALL SELECT 'rescue_session,' || count(*) FROM rescue_session
UNION ALL SELECT 'rescue_session_turn,' || count(*) FROM rescue_session_turn
UNION ALL SELECT 'session_deliverable,' || count(*) FROM session_deliverable
UNION ALL SELECT 'session_speech_briefing_cache,' || count(*) FROM session_speech_briefing_cache
UNION ALL SELECT 'session_turn,' || count(*) FROM session_turn
UNION ALL SELECT 'voice_command_telemetry,' || count(*) FROM voice_command_telemetry
UNION ALL SELECT 'voice_focus,' || count(*) FROM voice_focus
UNION ALL SELECT 'voice_note,' || count(*) FROM voice_note
UNION ALL SELECT 'voice_note_send_intent,' || count(*) FROM voice_note_send_intent
UNION ALL SELECT 'work_session,' || count(*) FROM work_session
ORDER BY 1;
SQL
}

expected_counts() {
  printf '%s\n' \
    'agent_run,0' \
    'api_usage_record,0' \
    'core_command,0' \
    'core_command_event,0' \
    'core_operator_context,0' \
    'managed_host,0' \
    'managed_service,0' \
    'managed_website,0' \
    'mobile_push_notification_log,0' \
    'operations_action_run,0' \
    'operations_incident,0' \
    'operator_account,1' \
    'operator_push_device,0' \
    'operator_refresh_token,0' \
    'operator_session_read_state,0' \
    'project,1' \
    'project_database_refresh_run,0' \
    'project_verification_run,0' \
    'rescue_session,0' \
    'rescue_session_turn,0' \
    'session_deliverable,0' \
    'session_speech_briefing_cache,0' \
    'session_turn,2' \
    'voice_command_telemetry,0' \
    'voice_focus,0' \
    'voice_note,0' \
    'voice_note_send_intent,0' \
    'work_session,1'
}

health_runtime() {
  for pair in \
    "${DB_CONTAINER}:db" \
    "${CODEX_CONTAINER}:codex-app-server" \
    "${APP_CONTAINER}:atenea-dev"; do
    local name="${pair%%:*}" service="${pair#*:}"
    container_exists "${name}" && container_owned "${name}" "${service}" ||
      return 1
    [[ "$(docker_cmd inspect -f '{{.State.Running}}' "${name}")" == true ]] ||
      return 1
  done

  local ready=false
  for unused in $(seq 1 180); do
    if rootlesskit_ports_ready &&
       [[ "$(docker_cmd inspect -f '{{.State.Health.Status}}' "${DB_CONTAINER}" 2>/dev/null || true)" == healthy ]] &&
       tcp_listener_ready "${CODEX_PORT}" &&
       timeout 3 curl -fsS "http://127.0.0.1:${WEB_PORT}/actuator/health" |
         jq -e '.status == "UP"' >/dev/null 2>&1; then
      ready=true
      break
    fi
    sleep 2
  done
  [[ "${ready}" == true ]] || return 1
  [[ "$(database_summary | tr -d '[:space:]')" == '45|1|45|45|0|45' ]] ||
    return 1
  diff -u <(expected_counts) <(domain_counts) >/dev/null || return 1

  docker_cmd container inspect "${CODEX_CONTAINER}" |
    jq -e '.[0].Config.Env | sort | index("ATENEA_CODEX_REQUIRED_AUTH_MODE=disabled") != null' \
      >/dev/null || return 1
  docker_cmd container inspect "${APP_CONTAINER}" |
    jq -e '
      .[0].Config.Env as $e |
      ($e | index("ATENEA_CODEX_REQUIRED_AUTH_MODE=disabled")) != null and
      ($e | index("ATENEA_OPENAI_ENABLED=false")) != null and
      ($e | index("ATENEA_OPENAI_COSTS_ENABLED=false")) != null and
      ($e | index("ATENEA_CORE_INTENT_ROUTER_ENABLED=false")) != null and
      ($e | index("ATENEA_BRIEFING_ENABLED=false")) != null and
      ($e | index("ATENEA_DEEPSEEK_COSTS_ENABLED=false")) != null and
      ($e | index("ATENEA_MOBILE_PUSH_ENABLED=false")) != null and
      ($e | index("ATENEA_GITHUB_API_BASE_URL=http://127.0.0.1:9")) != null and
      ($e | index("ATENEA_AUTH_BOOTSTRAP_ENABLED=false")) != null
    ' >/dev/null || return 1
}

retain_logs() {
  install -d -m 0750 "${LOGS_PATH}"
  for pair in \
    "${DB_CONTAINER}:db" \
    "${CODEX_CONTAINER}:codex-app-server" \
    "${APP_CONTAINER}:atenea-dev"; do
    local name="${pair%%:*}" service="${pair#*:}"
    if container_exists "${name}"; then
      container_owned "${name}" "${service}" ||
        fail RUNTIME_OWNERSHIP_CONFLICT "Cannot retain logs from a foreign container."
      docker_cmd logs --tail "$(jq -r '.logTail' "${PLAN}")" "${name}" \
        >"${LOGS_PATH}/${service}.log" 2>&1 || true
      chmod 0640 "${LOGS_PATH}/${service}.log"
      retain_container_inspect "${name}" "${LOGS_PATH}/${service}-container.json"
    fi
  done
  for secret in \
    "${SECRET_ROOT}/ATENEA_DEV_POSTGRES_PASSWORD" \
    "${SECRET_ROOT}/ATENEA_DEV_JWT_SECRET"; do
    if grep -R -F -f "${secret}" "${LOGS_PATH}" >/dev/null 2>&1; then
      fail OPERATION_FAILED "A development secret value appeared in retained runtime logs."
    fi
  done
}

stop_runtime() {
  assert_network_owned_or_absent
  assert_container_owned_or_absent "${DB_CONTAINER}" db
  assert_container_owned_or_absent "${CODEX_CONTAINER}" codex-app-server
  assert_container_owned_or_absent "${APP_CONTAINER}" atenea-dev
  remove_owned_rootless_ports
  if [[ -f "${COMPOSE_FILE}" && ! -L "${COMPOSE_FILE}" ]]; then
    docker_cmd compose \
      --project-name "${COMPOSE_PROJECT}" \
      --file "${COMPOSE_FILE}" stop --timeout 20 >/dev/null
  fi
}

emit_result() {
  local state="$1" health="$2" message="$3"
  if [[ "${JSON_MODE}" == true ]]; then
    jq -cn --arg state "${state}" --arg health "${health}" \
      '{state: $state, healthState: $health}'
  else
    printf '%s\n' "${message}"
  fi
}

validate_plan() {
  assert_regular "${PLAN}"
  SESSION="$(jq -r '.sessionId' "${PLAN}")"
  RUNTIME="$(jq -r '.runtimeId' "${PLAN}")"
  OPERATION="$(jq -r '.operation' "${PLAN}")"
  ALLOCATION="$(jq -r '.allocationPath' "${PLAN}")"
  MANIFEST="$(jq -r '.manifestPath' "${PLAN}")"
  WORKTREE="$(jq -r '.ateneaAdapter.compose.sourcePath' "${PLAN}" |
    sed 's#/ops/worker/docker-compose.ax42.yml$##')"
  DELIVERY="$(jq -r '.ateneaAdapter.compose.delivery.root' "${PLAN}")"
  SOURCE="$(jq -r '.ateneaAdapter.compose.delivery.source' "${PLAN}")"
  SLOT="$(jq -r '.slot' "${PLAN}")"
  SLOT_USER="atenea-${SLOT}"
  RUNTIME_ROOT="$(jq -r '.runtimeNames.tomcatBase' "${PLAN}" | sed 's#/tomcat$##')"
  LOGS_PATH="$(jq -r '.ateneaAdapter.compose.delivery.logs' "${PLAN}")"
  SECRET_ROOT="${RUNTIME_ROOT}/secrets"
  COMPOSE_PROJECT="$(jq -r '.runtimeNames.composeProject' "${PLAN}")"
  NETWORK="$(jq -r '.runtimeNames.network' "${PLAN}")"
  VOLUME="$(jq -r '.runtimeNames.volumePrefix' "${PLAN}")-db-data"
  DB_CONTAINER="${RUNTIME}-db"
  CODEX_CONTAINER="${RUNTIME}-codex-app-server"
  APP_CONTAINER="${RUNTIME}-atenea-dev"
  BUILD_CONTAINER="${RUNTIME}-build"
  BUILD_DB_CONTAINER="${RUNTIME}-build-db"
  BUILD_NETWORK="${RUNTIME}-build-network"
  ENGINE_ROOT="${RUNTIME_ROOT}/engine-v1"
  COMPOSE_FILE="${ENGINE_ROOT}/compose.atenea.generated.json"
  POSTGRES_PORT="$(jq -r '.allocatedPorts[] | select(.name == "postgres") | .loopbackPort' "${PLAN}")"
  CODEX_PORT="$(jq -r '.allocatedPorts[] | select(.name == "codex") | .loopbackPort' "${PLAN}")"
  WEB_PORT="$(jq -r '.allocatedPorts[] | select(.name == "web") | .loopbackPort' "${PLAN}")"

  [[ "${SESSION}" == "${EXPECTED_SESSION}" &&
      "${RUNTIME}" == "${EXPECTED_RUNTIME}" &&
      "${SLOT}" == slot3 &&
      "${SLOT_USER}" == atenea-slot3 &&
      "${DELIVERY}" == "${DELIVERY_BASE}/${RUNTIME}" &&
      "${SOURCE}" == "${DELIVERY}/source" ]] ||
    fail RUNTIME_OWNERSHIP_CONFLICT "The Atenea adapter accepts only the exact admitted WorkSession."
  [[ "$(jq -r '.projectId' "${PLAN}")" == atenea ]] ||
    fail RUNTIME_OWNERSHIP_CONFLICT "The Atenea adapter received another project."
  assert_regular "${ALLOCATION}"
  assert_regular "${MANIFEST}"
  assert_sha "${MANIFEST}" "${EXPECTED_MANIFEST_SHA256}"
  assert_sha "${WORKTREE}/ops/worker/docker-compose.ax42.yml" "${EXPECTED_COMPOSE_SHA256}"
  [[ "$(runuser -u atenea-worker -- git -C "${WORKTREE}" rev-parse HEAD)" == "${EXPECTED_COMMIT}" &&
      "$(runuser -u atenea-worker -- git -C "${WORKTREE}" rev-parse HEAD^{tree})" == "${EXPECTED_TREE}" ]] ||
    fail SESSION_IDENTITY_CONFLICT "The admitted Atenea source identity changed."
  runuser -u atenea-worker -- git -C "${WORKTREE}" diff --quiet ||
    fail RECONCILIATION_REQUIRED "The admitted Atenea worktree is dirty."
  runuser -u atenea-worker -- git -C "${WORKTREE}" diff --cached --quiet ||
    fail RECONCILIATION_REQUIRED "The admitted Atenea index is dirty."

  [[ -d "${ENGINE_ROOT}" && ! -L "${ENGINE_ROOT}" &&
      "$(stat -c %u:%a "${ENGINE_ROOT}")" == '0:700' ]] ||
    fail RUNTIME_OWNERSHIP_CONFLICT "The engine state root is unsafe."
  [[ -d "${LOGS_PATH}" && ! -L "${LOGS_PATH}" ]] ||
    fail RUNTIME_OWNERSHIP_CONFLICT "The runtime log root is unsafe."
  id "${SLOT_USER}" >/dev/null 2>&1 ||
    fail RUNTIME_OWNERSHIP_CONFLICT "The admitted rootless slot identity is absent."
  if [[ "${TEST_MODE}" != '1' ]]; then
    [[ -S /run/user/1103/docker.sock &&
        "$(stat -c %u:%a /run/user/1103/docker.sock)" == '1103:1660' ]] ||
      fail TOOLCHAIN_UNAVAILABLE "The exact slot3 exec-stream socket is unavailable."
  fi
}

execute() {
  PLAN="$(argument --plan "$@")" || usage
  DOCKER_HOST_VALUE="$(argument --docker-host "$@")" || usage
  for item in "$@"; do
    [[ "${item}" == --json ]] && JSON_MODE=true
  done
  [[ "${DOCKER_HOST_VALUE}" == 'unix:///run/atenea-runtime/slot3/docker.sock' ||
      ( "${TEST_MODE}" == 1 && "${DOCKER_HOST_VALUE}" == unix:///tmp/* ) ]] ||
    fail RUNTIME_OWNERSHIP_CONFLICT "The adapter Docker socket is outside slot3."
  [[ -S "${DOCKER_HOST_VALUE#unix://}" ||
      "${ATENEA_RUNTIME_FAKE_DOCKER:-0}" == 1 ]] ||
    fail TOOLCHAIN_UNAVAILABLE "The admitted rootless slot socket is unavailable."
  validate_plan

  case "${OPERATION}" in
    doctor)
      docker_cmd version >/dev/null
      assert_image "${CODEX_IMAGE}" "${CODEX_IMAGE_ID}"
      emit_result ready healthy 'Atenea mediated runtime prerequisites are ready.'
      ;;
    build)
      build_application
      emit_result ready unknown 'Atenea source delivery and application artifact are ready.'
      ;;
    up)
      start_runtime
      health_runtime ||
        fail HEALTH_CHECK_FAILED "The admitted Atenea runtime did not become healthy."
      retain_logs
      emit_result ready healthy 'Atenea private development runtime is healthy.'
      ;;
    status)
      if health_runtime; then
        emit_result ready healthy 'Atenea private development runtime is healthy.'
      elif container_exists "${APP_CONTAINER}"; then
        emit_result running unhealthy 'Atenea runtime exists but is not healthy.'
      else
        emit_result stopped stopped 'Atenea runtime is stopped.'
      fi
      ;;
    logs)
      retain_logs
      if health_runtime; then
        emit_result ready healthy "Atenea runtime logs were retained at ${LOGS_PATH}."
      else
        emit_result stopped stopped "Atenea runtime logs were retained at ${LOGS_PATH}."
      fi
      ;;
    stop)
      retain_logs
      stop_runtime
      emit_result stopped stopped 'Atenea runtime stopped; retained state was preserved.'
      ;;
    restart)
      stop_runtime
      start_runtime
      health_runtime ||
        fail HEALTH_CHECK_FAILED "The restarted Atenea runtime is unhealthy."
      emit_result ready healthy 'Atenea private development runtime restarted.'
      ;;
    redeploy)
      stop_runtime
      build_application
      start_runtime
      health_runtime ||
        fail HEALTH_CHECK_FAILED "The redeployed Atenea runtime is unhealthy."
      emit_result ready healthy 'Atenea private development runtime redeployed.'
      ;;
    *)
      fail OPERATION_FAILED "The Atenea adapter operation is unsupported."
      ;;
  esac
}

case "${ACTION}" in
  execute)
    execute "$@"
    ;;
  *)
    usage
    ;;
esac
