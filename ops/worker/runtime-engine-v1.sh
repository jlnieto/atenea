#!/usr/bin/env bash

set -Eeuo pipefail
umask 0077

ACTION="${1:-}"
[[ "$#" -gt 0 ]] && shift
TEST_MODE="${ATENEA_RUNTIME_ENGINE_TEST_MODE:-0}"
NODE_IMAGE="node:22.16.0-bookworm-slim@sha256:048ed02c5fd52e86fda6fbd2f6a76cf0d4492fd6c6fee9e2c463ed5108da0e34"
JDK17_IMAGE="eclipse-temurin:17-jdk-jammy@sha256:723151f3fc88ca2060153ee08ab8dbbea7983d6ed6f2622fe440acf178737c94"
TOMCAT8_IMAGE="tomcat:8.5.100-jre8-temurin-jammy@sha256:e3ca75a4b11560bfb30894c3fa5d066ff0105e2e8e1ad183711df97606321e51"
ENGINE_LABEL="atenea-runtime-engine-v1"
ATENEA_MANIFEST_SHA256="3b26e1899a06993bee69ac596e7cb69b6200a37d063d98203ad308058c91bfa3"
ATENEA_COMPOSE_SHA256="2133646b9fe6227ca417d6d62c92a74306caaa46a2957cdee810d5d7b0e5bb9f"
ATENEA_POSTGRES_IMAGE="postgres:16@sha256:33f923b05f64ca54ac4401c01126a6b92afe839a0aa0a52bc5aeb5cc958e5f20"
ATENEA_CODEX_IMAGE="atenea/codex-app-server@sha256:c081aaa9d40afa4d8b57297000fe9aff5635e52a94b2b87abf8626b128c55e2d"
ATENEA_APP_IMAGE="maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e"
ATENEA_ADAPTER="${ATENEA_RUNTIME_ATENEA_ADAPTER:-/usr/libexec/atenea-runtime-engine-adapter-v1}"

fail() {
  printf '%s: %s\n' "$1" "$2" >&2
  exit 65
}

usage() {
  cat >&2 <<EOF
Usage:
  $0 inspect --session <uuid> --allocation <path> --manifest <path>
  $0 execute --plan <runtime-plan-v1.json> [--json]
EOF
  exit 64
}

for command in curl flock jq realpath sha256sum stat timeout; do
  command -v "${command}" >/dev/null ||
    fail "OPERATION_FAILED" "A fixed engine prerequisite is unavailable."
done

if [[ "${TEST_MODE}" == "1" ]]; then
  DOCKER_BIN="${ATENEA_RUNTIME_DOCKER_BIN:-}"
  [[ "${DOCKER_BIN}" == /tmp/* && "${DOCKER_BIN}" != *".."* &&
      -f "${DOCKER_BIN}" && ! -L "${DOCKER_BIN}" && -x "${DOCKER_BIN}" ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "Synthetic Docker client is unsafe."
else
  [[ "${EUID}" -eq 0 ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "The runtime engine requires the mediated root boundary."
  DOCKER_BIN="/usr/bin/docker"
  [[ -x "${DOCKER_BIN}" && ! -L "${DOCKER_BIN}" ]] ||
    fail "TOOLCHAIN_UNAVAILABLE" "The pinned Docker client is unavailable."
fi

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

assert_regular() {
  local path="$1"
  [[ -f "${path}" && ! -L "${path}" ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "A required runtime input is missing or unsafe."
}

assert_sha256() {
  local path="$1" expected="$2"
  [[ "$(sha256sum "${path}" | cut -d' ' -f1)" == "${expected}" ]] ||
    fail "MANIFEST_INVALID" "A runtime input differs from its exact reviewed version."
}

validate_atenea() {
  local allocation="$1" manifest="$2"
  assert_regular "${allocation}"
  assert_regular "${manifest}"
  local worktree worktree_real manifest_real compose
  worktree="$(jq -r '.worktreePath' "${allocation}")"
  worktree_real="$(realpath -e "${worktree}")"
  manifest_real="$(realpath -e "${manifest}")"
  [[ "${manifest_real}" == "${worktree_real}/ops/atenea-runtime.json" ]] ||
    fail "MANIFEST_INVALID" "The Atenea manifest path is not the fixed reviewed path."
  assert_sha256 "${manifest_real}" "${ATENEA_MANIFEST_SHA256}"
  compose="${worktree_real}/ops/worker/docker-compose.ax42.yml"
  assert_regular "${compose}"
  assert_sha256 "${compose}" "${ATENEA_COMPOSE_SHA256}"
  jq -e '
    (keys | sort) == [
      "allocatedPorts", "artifactsRoot", "branch", "cacheRoot",
      "heavyPermit", "logsPath", "manifestRelativePath", "mirrorPath",
      "projectId", "runtimeId", "runtimeNames", "runtimeRoot",
      "schemaVersion", "sessionId", "slot", "state", "workloadClass",
      "worktreePath"
    ] and
    .schemaVersion == 1 and .state == "allocated" and
    (.sessionId |
      test("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")) and
    .projectId == "atenea" and .workloadClass == "heavy" and
    (.heavyPermit | test("^heavy[1-2]$")) and
    (.slot | test("^slot[1-4]$")) and
    .manifestRelativePath == "ops/atenea-runtime.json" and
    .runtimeId == ("ws-" + (.sessionId | gsub("-"; ""))) and
    .worktreePath == (
      ($ENV.ATENEA_ENGINE_WORKSPACE_ROOT // "/srv/atenea/workspaces") +
      "/sessions/" + .sessionId + "/atenea"
    ) and
    .runtimeRoot == (
      ($ENV.ATENEA_ENGINE_WORKSPACE_ROOT // "/srv/atenea/workspaces") +
      "/sessions/" + .sessionId + "/runtime/" + .runtimeId
    ) and
    .logsPath == (
      ($ENV.ATENEA_ENGINE_ARTIFACT_ROOT // "/srv/atenea/artifacts") +
      "/sessions/" + .sessionId + "/runtime/logs"
    ) and
    .artifactsRoot == (
      ($ENV.ATENEA_ENGINE_ARTIFACT_ROOT // "/srv/atenea/artifacts") +
      "/sessions/" + .sessionId + "/runs"
    ) and
    .cacheRoot == (
      ($ENV.ATENEA_ENGINE_CACHE_ROOT // "/srv/atenea/caches") +
      "/sessions/" + .sessionId
    ) and
    .runtimeNames.composeProject == (.runtimeId + "-compose") and
    .runtimeNames.network == (.runtimeId + "-network") and
    .runtimeNames.volumePrefix == (.runtimeId + "-volume") and
    .runtimeNames.processUnit == ("atenea-" + .runtimeId + ".service") and
    .runtimeNames.tomcatBase == (.runtimeRoot + "/tomcat") and
    ([.allocatedPorts[] | {
      name: .name,
      internalPort: .internalPort,
      protocol: .protocol,
      bindAddress: .bindAddress
    }] | sort_by(.name)) == [
      {name: "codex", internalPort: 8092, protocol: "tcp", bindAddress: "127.0.0.1"},
      {name: "postgres", internalPort: 5432, protocol: "tcp", bindAddress: "127.0.0.1"},
      {name: "web", internalPort: 8081, protocol: "http", bindAddress: "127.0.0.1"}
    ] and
    ([.allocatedPorts[].loopbackPort] | length == 3 and
      length == (unique | length) and
      all(.[]; type == "number" and floor == . and . >= 1024 and . <= 65535))
  ' "${allocation}" >/dev/null ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "The Atenea allocation is not an exact session-owned heavy allocation."
}

atenea_inspection() {
  local session="$1" allocation="$2" manifest="$3"
  validate_atenea "${allocation}" "${manifest}"
  local compose
  compose="$(realpath -e "$(jq -r '.worktreePath' "${allocation}")/ops/worker/docker-compose.ax42.yml")"
  jq -cn \
    --arg session "${session}" \
    --arg engine "${ENGINE_LABEL}" \
    --arg manifestSha "${ATENEA_MANIFEST_SHA256}" \
    --arg allocationSha "$(sha256sum "${allocation}" | cut -d' ' -f1)" \
    --arg composePath "${compose}" \
    --arg composeSha "${ATENEA_COMPOSE_SHA256}" \
    --arg postgresImage "${ATENEA_POSTGRES_IMAGE}" \
    --arg codexImage "${ATENEA_CODEX_IMAGE}" \
    --arg appImage "${ATENEA_APP_IMAGE}" \
    --arg deliveryBase "${ATENEA_RUNTIME_DELIVERY_BASE:-/tmp/atenea-runtime-delivery}" \
    --slurpfile allocation "${allocation}" '
      $allocation[0] as $a |
      ($deliveryBase + "/" + $a.runtimeId) as $delivery |
      def labels($service): {
        "com.atenea.engine": $engine,
        "com.atenea.session": $session,
        "com.atenea.runtime": $a.runtimeId,
        "com.atenea.project": "atenea",
        "com.atenea.service": $service
      };
      def port($name):
        $a.allocatedPorts[] | select(.name == $name) |
        {
          name: .name,
          internalPort: .internalPort,
          protocol: .protocol,
          bindAddress: .bindAddress,
          loopbackPort: .loopbackPort
        };
      {
        schemaVersion: 1,
        sessionId: $session,
        runtimeId: $a.runtimeId,
        projectId: "atenea",
        slot: $a.slot,
        allocationSha256: $allocationSha,
        manifestSha256: $manifestSha,
        compose: {
          sourcePath: $composePath,
          sourceSha256: $composeSha,
          projectName: $a.runtimeNames.composeProject,
          delivery: {
            root: $delivery,
            source: ($delivery + "/source"),
            archiveSha256: "a6f52b2d267750dfb4f8bc9f31d3c0d2434876ddf6517920cb882f19112b5dea",
            commit: "b6dc854d94ba5b1976926656c9a6aba330f671e2",
            tree: "f8c0dff5c7acf3d82d73885b09f9b1d142b562d2",
            logs: $a.logsPath
          },
          network: {
            name: $a.runtimeNames.network,
            internal: true,
            labels: labels("runtime")
          },
          volumes: [{
            name: ($a.runtimeNames.volumePrefix + "-db-data"),
            labels: labels("db")
          }]
        },
        services: [
          {
            name: "db",
            image: $postgresImage,
            containerName: ($a.runtimeId + "-db"),
            mounts: [{
              type: "volume",
              source: ($a.runtimeNames.volumePrefix + "-db-data"),
              target: "/var/lib/postgresql/data",
              readOnly: false
            }],
            ports: [port("postgres")],
            secretRefs: ["ATENEA_DEV_POSTGRES_PASSWORD"],
            labels: labels("db"),
            resourceNames: [
              $a.runtimeNames.composeProject,
              $a.runtimeNames.network,
              ($a.runtimeNames.volumePrefix + "-db-data"),
              ($a.runtimeId + "-db")
            ],
            namespaces: [],
            capabilities: [],
            devices: [],
            daemonSockets: [],
            unsupportedFields: []
          },
          {
            name: "codex-app-server",
            image: $codexImage,
            containerName: ($a.runtimeId + "-codex-app-server"),
            mounts: [
              {
                type: "bind",
                source: ($delivery + "/source"),
                target: "/workspace/atenea",
                readOnly: false
              },
              {
                type: "bind",
                source: ($delivery + "/cache/codex"),
                target: "/workspace/cache/codex",
                readOnly: false
              }
            ],
            ports: [port("codex")],
            secretRefs: [],
            labels: labels("codex-app-server"),
            resourceNames: [
              $a.runtimeNames.composeProject,
              $a.runtimeNames.network,
              ($a.runtimeId + "-codex-app-server")
            ],
            namespaces: [],
            capabilities: [],
            devices: [],
            daemonSockets: [],
            unsupportedFields: []
          },
          {
            name: "atenea-dev",
            image: $appImage,
            containerName: ($a.runtimeId + "-atenea-dev"),
            mounts: [
              {
                type: "bind",
                source: ($delivery + "/source"),
                target: "/workspace/atenea",
                readOnly: false
              },
              {
                type: "bind",
                source: ($delivery + "/cache/maven"),
                target: "/workspace/cache/maven",
                readOnly: false
              },
              {
                type: "bind",
                source: ($delivery + "/cache/node"),
                target: "/workspace/cache/node",
                readOnly: false
              },
              {
                type: "bind",
                source: ($delivery + "/data/uploads"),
                target: "/workspace/data/uploads",
                readOnly: false
              }
            ],
            ports: [port("web")],
            secretRefs: [
              "ATENEA_DEV_POSTGRES_PASSWORD",
              "ATENEA_DEV_JWT_SECRET"
            ],
            labels: labels("atenea-dev"),
            resourceNames: [
              $a.runtimeNames.composeProject,
              $a.runtimeNames.network,
              ($a.runtimeId + "-atenea-dev")
            ],
            namespaces: [],
            capabilities: [],
            devices: [],
            daemonSockets: [],
            unsupportedFields: []
          }
        ]
      }
    '
}

validate_fixture() {
  local allocation="$1" manifest="$2"
  assert_regular "${allocation}"
  assert_regular "${manifest}"
  local project worktree manifest_real worktree_real kind
  project="$(jq -r '.projectId' "${allocation}")"
  worktree="$(jq -r '.worktreePath' "${allocation}")"
  worktree_real="$(realpath -e "${worktree}")"
  manifest_real="$(realpath -e "${manifest}")"
  [[ "${manifest_real}" == "${worktree_real}/runtime.json" ]] ||
    fail "MANIFEST_INVALID" "The fixture manifest path is not fixed by the engine."
  kind="$(jq -r '.runtime.kind' "${manifest}")"

  case "${project}:${kind}" in
    dummy-compose:compose)
      jq -e '
        .project.id == "dummy-compose" and
        .runtime.composeFiles == ["compose.json"] and
        .runtime.services == ["app"] and
        .runtime.internalPorts == [
          {name: "web", port: 8080, protocol: "http"}
        ] and .secrets == [] and .workloadClass == "normal"
      ' "${manifest}" >/dev/null ||
        fail "MANIFEST_INVALID" "The Compose fixture contract changed."
      assert_regular "${worktree_real}/compose.json"
      assert_regular "${worktree_real}/server.js"
      assert_sha256 "${worktree_real}/compose.json" \
        "bd49af9c20a53f777ad58a3f00f7dedb2c04a7d8922ee6a4463ad0976ba99dd4"
      assert_sha256 "${worktree_real}/server.js" \
        "741ec41cf108ee7727ed3c6f5b497cb218e5413cd6686fb09eb1f00488e79407"
      jq -e '
        (keys | sort) == ["services"] and
        (.services | keys) == ["app"] and
        (.services.app | keys) == ["build"] and
        .services.app.build == {context: ".", dockerfile: "Dockerfile"}
      ' "${worktree_real}/compose.json" >/dev/null ||
        fail "MANIFEST_INVALID" "The Compose fixture requests unsupported authority."
      grep -Fq 'listen(8080, "0.0.0.0")' "${worktree_real}/server.js" ||
        fail "MANIFEST_INVALID" "The Compose fixture source is incompatible."
      ;;
    dummy-tomcat:tomcat)
      jq -e '
        .project.id == "dummy-tomcat" and
        .runtime.webappModule == "src" and
        .runtime.warPath == "target/fixture.war" and
        .runtime.contextPath == "/" and
        .runtime.internalPorts == [
          {name: "web", port: 8080, protocol: "http"}
        ] and .secrets == [] and .workloadClass == "normal" and
        any(.toolchains[];
          .name == "java-build" and .version == "17.0.19" and
          .source == "container-image") and
        any(.toolchains[];
          .name == "java-runtime" and .version == "8.0.402" and
          .source == "container-image")
      ' "${manifest}" >/dev/null ||
        fail "MANIFEST_INVALID" "The Tomcat fixture contract changed."
      assert_regular "${worktree_real}/src/FixtureServlet.java"
      assert_regular "${worktree_real}/src/web.xml"
      assert_sha256 "${worktree_real}/src/FixtureServlet.java" \
        "883aacfc9ea0bfef289872097225da17f7877577786ca44740077ab760781d2e"
      assert_sha256 "${worktree_real}/src/web.xml" \
        "48cdf96d7ada9fb77b06def80167456649810d3c5ac53708f1b31869f67dad0d"
      grep -Fq 'extends HttpServlet' "${worktree_real}/src/FixtureServlet.java" &&
        grep -Fq '<url-pattern>/</url-pattern>' "${worktree_real}/src/web.xml" ||
        fail "MANIFEST_INVALID" "The Tomcat fixture source is incompatible."
      ;;
    *)
      fail "MANIFEST_INVALID" "Runtime engine v1 accepts only the two task 4.3 fixtures."
      ;;
  esac
}

inspect_fixture() {
  local session allocation manifest runtime kind project compose network unit tomcat
  session="$(argument --session "$@")" || usage
  allocation="$(argument --allocation "$@")" || usage
  manifest="$(argument --manifest "$@")" || usage
  [[ "$#" -eq 6 ]] || usage
  if [[ "$(jq -r '.projectId' "${allocation}")" == "atenea" ]]; then
    [[ "$(jq -r '.sessionId' "${allocation}")" == "${session}" ]] ||
      fail "SESSION_IDENTITY_CONFLICT" "Atenea inspection identity does not match."
    atenea_inspection "${session}" "${allocation}" "${manifest}"
    return
  fi
  validate_fixture "${allocation}" "${manifest}"
  runtime="$(jq -r '.runtimeId' "${allocation}")"
  project="$(jq -r '.projectId' "${allocation}")"
  kind="$(jq -r '.runtime.kind' "${manifest}")"
  [[ "$(jq -r '.sessionId' "${allocation}")" == "${session}" ]] ||
    fail "SESSION_IDENTITY_CONFLICT" "Fixture inspection identity does not match."
  compose="$(jq -r '.runtimeNames.composeProject' "${allocation}")"
  network="$(jq -r '.runtimeNames.network' "${allocation}")"
  unit="$(jq -r '.runtimeNames.processUnit' "${allocation}")"
  tomcat="$(jq -r '.runtimeNames.tomcatBase' "${allocation}")"
  if [[ "${kind}" == "compose" ]]; then
    resources="$(jq -cn --arg compose "${compose}" --arg network "${network}" \
      '[$compose, $network]')"
    service="app"
  else
    resources="$(jq -cn --arg unit "${unit}" --arg tomcat "${tomcat}" \
      '[$unit, $tomcat]')"
    service="tomcat"
  fi
  jq -cn \
    --arg session "${session}" \
    --arg runtime "${runtime}" \
    --arg service "${service}" \
    --argjson resources "${resources}" '{
      schemaVersion: 1,
      sessionId: $session,
      runtimeId: $runtime,
      services: [{
        name: $service,
        mounts: [],
        namespaces: [],
        capabilities: [],
        devices: [],
        daemonSockets: [],
        resourceNames: $resources,
        unsupportedFields: []
      }]
    }'
}

PLAN=""
JSON_MODE=false
DOCKER_HOST_VALUE=""
SESSION=""
PROJECT=""
RUNTIME=""
ALLOCATION=""
MANIFEST=""
RUNTIME_ROOT=""
LOGS_PATH=""
ARTIFACTS_ROOT=""
LOOPBACK_PORT=""
SLOT=""
KIND=""
CONTAINER=""
IMAGE=""
NETWORK=""
COMPOSE_PROJECT=""
ENGINE_ROOT=""
LOCK_PATH=""

docker_cmd() {
  DOCKER_HOST="${DOCKER_HOST_VALUE}" timeout --foreground 600 "${DOCKER_BIN}" "$@"
}

resource_labels_match() {
  local object_type="$1" object="$2"
  docker_cmd "${object_type}" inspect \
    --format '{{ index .Config.Labels "com.atenea.engine" }} {{ index .Config.Labels "com.atenea.session" }} {{ index .Config.Labels "com.atenea.runtime" }}' \
    "${object}" 2>/dev/null |
    grep -Fxq "${ENGINE_LABEL} ${SESSION} ${RUNTIME}"
}

network_labels_match() {
  local object="$1"
  docker_cmd network inspect \
    --format '{{ index .Labels "com.atenea.engine" }} {{ index .Labels "com.atenea.session" }} {{ index .Labels "com.atenea.runtime" }}' \
    "${object}" 2>/dev/null |
    grep -Fxq "${ENGINE_LABEL} ${SESSION} ${RUNTIME}"
}

container_exists() {
  docker_cmd container inspect "${CONTAINER}" >/dev/null 2>&1
}

assert_container_owned() {
  container_exists || return 1
  resource_labels_match container "${CONTAINER}" ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "A runtime container has foreign ownership."
}

assert_image_owned_or_absent() {
  if docker_cmd image inspect "${IMAGE}" >/dev/null 2>&1; then
    resource_labels_match image "${IMAGE}" ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "A runtime image has foreign ownership."
  fi
}

assert_network_owned_or_absent() {
  if docker_cmd network inspect "${NETWORK}" >/dev/null 2>&1; then
    network_labels_match "${NETWORK}" ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "A runtime network has foreign ownership."
  fi
}

snapshot_fixture() {
  local worktree snapshot temporary
  worktree="$(jq -r '.worktreePath' "${ALLOCATION}")"
  snapshot="${ENGINE_ROOT}/source"
  temporary="${ENGINE_ROOT}/.source.$$"
  install -d -m 0700 "${temporary}"
  if [[ "${KIND}" == "compose" ]]; then
    install -m 0600 "${worktree}/server.js" "${temporary}/server.js"
    cmp -s "${worktree}/server.js" "${temporary}/server.js" ||
      fail "RECONCILIATION_REQUIRED" "Compose fixture changed during snapshot."
  else
    install -d -m 0700 "${temporary}/src"
    install -m 0600 \
      "${worktree}/src/FixtureServlet.java" "${temporary}/src/FixtureServlet.java"
    install -m 0600 "${worktree}/src/web.xml" "${temporary}/src/web.xml"
    cmp -s "${worktree}/src/FixtureServlet.java" \
      "${temporary}/src/FixtureServlet.java" &&
      cmp -s "${worktree}/src/web.xml" "${temporary}/src/web.xml" ||
      fail "RECONCILIATION_REQUIRED" "Tomcat fixture changed during snapshot."
  fi
  if [[ -d "${snapshot}" ]]; then
    [[ ! -L "${snapshot}" &&
        "$(stat -c %u "${snapshot}")" == "$(id -u)" &&
        -f "${snapshot}/.owner-v1" && ! -L "${snapshot}/.owner-v1" &&
        "$(cat "${snapshot}/.owner-v1")" == "${SESSION} ${RUNTIME}" ]] ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "Fixture snapshot has foreign ownership."
    rm -rf -- "${snapshot}"
  fi
  printf '%s %s\n' "${SESSION}" "${RUNTIME}" >"${temporary}/.owner-v1"
  mv -- "${temporary}" "${snapshot}"
}

write_dockerfile() {
  local dockerfile="${ENGINE_ROOT}/source/Dockerfile"
  if [[ "${KIND}" == "compose" ]]; then
    cat >"${dockerfile}" <<EOF
FROM ${NODE_IMAGE}
WORKDIR /fixture
COPY server.js /fixture/server.js
LABEL com.atenea.engine="${ENGINE_LABEL}"
LABEL com.atenea.session="${SESSION}"
LABEL com.atenea.runtime="${RUNTIME}"
LABEL com.atenea.fixture.build-node="22.16.0"
EXPOSE 8080
CMD ["node", "/fixture/server.js"]
EOF
  else
    cat >"${dockerfile}" <<EOF
FROM ${TOMCAT8_IMAGE} AS tomcat-api
FROM ${JDK17_IMAGE} AS build
COPY --from=tomcat-api /usr/local/tomcat/lib/servlet-api.jar /deps/servlet-api.jar
COPY src/FixtureServlet.java /src/FixtureServlet.java
COPY src/web.xml /src/web.xml
RUN mkdir -p /build/WEB-INF/classes && \
    javac -version && \
    javac --release 8 -cp /deps/servlet-api.jar \
      -d /build/WEB-INF/classes /src/FixtureServlet.java && \
    cp /src/web.xml /build/WEB-INF/web.xml && \
    cd /build && jar cf /fixture.war .
FROM ${TOMCAT8_IMAGE}
RUN rm -rf /usr/local/tomcat/webapps/* && \
    mkdir -p /usr/local/tomcat/webapps/ROOT
COPY --from=build /build/ /usr/local/tomcat/webapps/ROOT/
COPY --from=build /fixture.war /opt/atenea-fixture/fixture.war
LABEL com.atenea.engine="${ENGINE_LABEL}"
LABEL com.atenea.session="${SESSION}"
LABEL com.atenea.runtime="${RUNTIME}"
LABEL com.atenea.fixture.build-jdk="17.0.19"
LABEL com.atenea.fixture.runtime-java="8"
LABEL com.atenea.fixture.runtime-tomcat="8.5.100"
EXPOSE 8080
EOF
  fi
  chmod 0600 "${dockerfile}"
}

write_compose() {
  jq -n \
    --arg image "${IMAGE}" \
    --arg container "${CONTAINER}" \
    --arg network "${NETWORK}" \
    --arg session "${SESSION}" \
    --arg runtime "${RUNTIME}" \
    --arg engine "${ENGINE_LABEL}" \
    --arg port "127.0.0.1:${LOOPBACK_PORT}:8080" '{
      services: {
        app: {
          image: $image,
          container_name: $container,
          ports: [$port],
          read_only: true,
          tmpfs: ["/tmp:rw,noexec,nosuid,size=16m"],
          cap_drop: ["ALL"],
          security_opt: ["no-new-privileges:true"],
          restart: "no",
          pids_limit: 128,
          labels: {
            "com.atenea.engine": $engine,
            "com.atenea.session": $session,
            "com.atenea.runtime": $runtime
          },
          networks: ["runtime"]
        }
      },
      networks: {
        runtime: {
          name: $network,
          labels: {
            "com.atenea.engine": $engine,
            "com.atenea.session": $session,
            "com.atenea.runtime": $runtime
          }
        }
      }
    }' >"${ENGINE_ROOT}/compose.generated.json"
  chmod 0600 "${ENGINE_ROOT}/compose.generated.json"
}

build_fixture() {
  assert_image_owned_or_absent
  snapshot_fixture
  write_dockerfile
  if ! docker_cmd build \
    --network none \
    --label "com.atenea.engine=${ENGINE_LABEL}" \
    --label "com.atenea.session=${SESSION}" \
    --label "com.atenea.runtime=${RUNTIME}" \
    --tag "${IMAGE}" \
    "${ENGINE_ROOT}/source" >"${LOGS_PATH}/build.log" 2>&1; then
    fail "OPERATION_FAILED" "The synthetic fixture build failed."
  fi
  resource_labels_match image "${IMAGE}" ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "The built image lacks session ownership."
  if [[ "${KIND}" == "tomcat" ]]; then
    local artifact="${ARTIFACTS_ROOT}/fixture-build"
    install -d -m 0750 "${artifact}"
    docker_cmd image inspect \
      --format '{{ index .Config.Labels "com.atenea.fixture.build-jdk" }} {{ index .Config.Labels "com.atenea.fixture.runtime-java" }} {{ index .Config.Labels "com.atenea.fixture.runtime-tomcat" }}' \
      "${IMAGE}" >"${artifact}/toolchain.txt"
    chmod 0640 "${artifact}/toolchain.txt"
  fi
}

start_fixture() {
  assert_image_owned_or_absent
  docker_cmd image inspect "${IMAGE}" >/dev/null 2>&1 ||
    build_fixture
  assert_network_owned_or_absent
  if container_exists; then
    assert_container_owned
    running="$(docker_cmd container inspect --format '{{.State.Running}}' "${CONTAINER}")"
    [[ "${running}" == "true" ]] || docker_cmd start "${CONTAINER}" >/dev/null
  elif [[ "${KIND}" == "compose" ]]; then
    write_compose
    docker_cmd compose \
      --project-name "${COMPOSE_PROJECT}" \
      --file "${ENGINE_ROOT}/compose.generated.json" \
      up --detach --no-build >/dev/null
  else
    if ! docker_cmd network inspect "${NETWORK}" >/dev/null 2>&1; then
      docker_cmd network create \
        --label "com.atenea.engine=${ENGINE_LABEL}" \
        --label "com.atenea.session=${SESSION}" \
        --label "com.atenea.runtime=${RUNTIME}" \
        "${NETWORK}" >/dev/null
    fi
    docker_cmd run --detach \
      --name "${CONTAINER}" \
      --network "${NETWORK}" \
      --publish "127.0.0.1:${LOOPBACK_PORT}:8080" \
      --read-only \
      --tmpfs /usr/local/tomcat/logs:rw,noexec,nosuid,size=16m \
      --tmpfs /usr/local/tomcat/temp:rw,noexec,nosuid,size=16m \
      --tmpfs /usr/local/tomcat/work:rw,noexec,nosuid,size=32m \
      --cap-drop ALL \
      --security-opt no-new-privileges:true \
      --pids-limit 256 \
      --restart no \
      --label "com.atenea.engine=${ENGINE_LABEL}" \
      --label "com.atenea.session=${SESSION}" \
      --label "com.atenea.runtime=${RUNTIME}" \
      "${IMAGE}" >/dev/null
  fi
}

health_fixture() {
  assert_container_owned || return 1
  [[ "$(docker_cmd container inspect --format '{{.State.Running}}' "${CONTAINER}")" == "true" ]] ||
    return 1
  for unused in {1..40}; do
    if timeout 3 curl -fsS "http://127.0.0.1:${LOOPBACK_PORT}/" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.25
  done
  return 1
}

stop_fixture() {
  if container_exists; then
    assert_container_owned
    docker_cmd stop --time 10 "${CONTAINER}" >/dev/null
  fi
}

remove_container_for_redeploy() {
  if container_exists; then
    assert_container_owned
    docker_cmd rm "${CONTAINER}" >/dev/null
  fi
}

retain_logs() {
  install -d -m 0750 "${LOGS_PATH}"
  if container_exists; then
    assert_container_owned
    docker_cmd logs --tail "$(jq -r '.logTail' "${PLAN}")" "${CONTAINER}" \
      >"${LOGS_PATH}/runtime.log" 2>&1 || true
    chmod 0640 "${LOGS_PATH}/runtime.log"
  fi
  printf 'Synthetic %s logs retained at %s\n' "${PROJECT}" "${LOGS_PATH}/runtime.log"
}

emit_result() {
  local state="$1" health="$2" message="${3:-}"
  if [[ "${JSON_MODE}" == "true" ]]; then
    jq -cn --arg state "${state}" --arg health "${health}" \
      '{state: $state, healthState: $health}'
  elif [[ -n "${message}" ]]; then
    printf '%s\n' "${message}"
  else
    printf 'Synthetic runtime state: %s; health: %s\n' "${state}" "${health}"
  fi
}

validate_plan() {
  assert_regular "${PLAN}"
  expected_plan_owner="${ATENEA_RUNTIME_PLAN_OWNER_UID:-$(id -u)}"
  [[ "$(stat -c %u "${PLAN}")" == "${expected_plan_owner}" &&
      "$(stat -c %a "${PLAN}")" == "600" ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "The runtime plan ownership or mode is unsafe."
  SESSION="$(jq -r '.sessionId' "${PLAN}")"
  PROJECT="$(jq -r '.projectId' "${PLAN}")"
  RUNTIME="$(jq -r '.runtimeId' "${PLAN}")"
  ALLOCATION="$(jq -r '.allocationPath' "${PLAN}")"
  MANIFEST="$(jq -r '.manifestPath' "${PLAN}")"
  if [[ "${PROJECT}" == "atenea" ]]; then
    validate_atenea "${ALLOCATION}" "${MANIFEST}"
    expected_adapter="$(atenea_inspection "${SESSION}" "${ALLOCATION}" "${MANIFEST}")"
    jq -e \
      --argjson expectedAdapter "${expected_adapter}" '
        (keys | sort) == [
          "allocatedPorts", "allocationPath", "ateneaAdapter", "logTail",
          "manifestPath", "operation", "projectId", "restrictions",
          "runtimeId", "runtimeNames", "schemaVersion", "sessionId", "slot"
        ] and
        .schemaVersion == 1 and .projectId == "atenea" and
        (.operation |
          . == "status" or . == "doctor" or . == "build" or . == "up" or
          . == "stop" or . == "restart" or . == "redeploy" or . == "logs") and
        .ateneaAdapter == $expectedAdapter and .slot == $expectedAdapter.slot and
        .allocatedPorts == (
          $expectedAdapter.services | map(.ports[]) | sort_by(.name)
        ) and
        .restrictions == {
          noNewPrivileges: true,
          readOnlyRootFilesystem: true,
          dropAllCapabilities: true,
          hostNetwork: false,
          hostPid: false,
          hostIpc: false,
          devicesAllowed: false,
          daemonSocketsAllowed: false,
          mountsAllowed: (
            [$expectedAdapter.services[].mounts[] |
              select(.type == "bind") | .source] | unique | sort
          ),
          secretRefsAllowed: [
            "ATENEA_DEV_JWT_SECRET",
            "ATENEA_DEV_POSTGRES_PASSWORD"
          ]
        }
      ' "${PLAN}" >/dev/null ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "The Atenea runtime plan is incompatible."
    jq -e \
      --arg session "${SESSION}" \
      --arg runtime "${RUNTIME}" \
      --arg slot "$(jq -r '.slot' "${PLAN}")" '
        .sessionId == $session and .runtimeId == $runtime and
        .slot == $slot and .state == "allocated"
      ' "${ALLOCATION}" >/dev/null ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "The Atenea plan does not own the assigned slot."
    SLOT="$(jq -r '.slot' "${ALLOCATION}")"
    [[ "${SLOT}" == "${ATENEA_RUNTIME_ALLOWED_SLOT:-slot2}" ]] ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "The engine is not authorized for this slot."
    RUNTIME_ROOT="$(jq -r '.runtimeRoot' "${ALLOCATION}")"
    LOGS_PATH="$(jq -r '.logsPath' "${ALLOCATION}")"
    ARTIFACTS_ROOT="$(jq -r '.artifactsRoot' "${ALLOCATION}")"
    NETWORK="$(jq -r '.runtimeNames.network' "${ALLOCATION}")"
    COMPOSE_PROJECT="$(jq -r '.runtimeNames.composeProject' "${ALLOCATION}")"
    ENGINE_ROOT="${RUNTIME_ROOT}/engine-v1"
    LOCK_PATH="${RUNTIME_ROOT}/engine-v1.lock"
    for path in "${RUNTIME_ROOT}" "${LOGS_PATH}" "${ARTIFACTS_ROOT}"; do
      [[ -d "${path}" && ! -L "${path}" ]] ||
        fail "RUNTIME_OWNERSHIP_CONFLICT" "A session-derived runtime path is unsafe."
    done
    if [[ -e "${ENGINE_ROOT}" || -L "${ENGINE_ROOT}" ]]; then
      [[ -d "${ENGINE_ROOT}" && ! -L "${ENGINE_ROOT}" &&
          "$(stat -c %u "${ENGINE_ROOT}")" == "$(id -u)" &&
          "$(stat -c %a "${ENGINE_ROOT}")" == "700" &&
          -f "${ENGINE_ROOT}/.owner-v1" &&
          "$(cat "${ENGINE_ROOT}/.owner-v1")" == "${SESSION} ${RUNTIME}" ]] ||
        fail "RUNTIME_OWNERSHIP_CONFLICT" "The engine state root has foreign ownership."
    else
      install -d -m 0700 "${ENGINE_ROOT}"
      chmod g-s,u=rwx,go= "${ENGINE_ROOT}"
      printf '%s %s\n' "${SESSION}" "${RUNTIME}" >"${ENGINE_ROOT}/.owner-v1"
    fi
    [[ ! -L "${LOCK_PATH}" ]] ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "The runtime lock is unsafe."
    exec {engine_lock_fd}>"${LOCK_PATH}"
    [[ "$(stat -c %u "${LOCK_PATH}")" == "$(id -u)" ]] ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "The runtime lock has foreign ownership."
    flock -w 30 "${engine_lock_fd}" ||
      fail "RECONCILIATION_REQUIRED" "The session runtime is busy."
    if [[ "${TEST_MODE}" == "1" ]]; then
      DOCKER_HOST_VALUE="${ATENEA_RUNTIME_DOCKER_HOST:-}"
      [[ "${DOCKER_HOST_VALUE}" == unix:///tmp/* ||
          "${DOCKER_HOST_VALUE}" == "unix:///run/atenea-runtime/slot2/docker.sock" ]] ||
        fail "RUNTIME_OWNERSHIP_CONFLICT" "Synthetic Docker host is outside the allowed slot."
      [[ "${ATENEA_ADAPTER}" == /tmp/* && -f "${ATENEA_ADAPTER}" &&
          ! -L "${ATENEA_ADAPTER}" && -x "${ATENEA_ADAPTER}" ]] ||
        fail "RUNTIME_OWNERSHIP_CONFLICT" "The synthetic Atenea adapter is unsafe."
    else
      DOCKER_HOST_VALUE="unix:///run/atenea-runtime/${SLOT}/docker.sock"
      [[ -f "${ATENEA_ADAPTER}" && ! -L "${ATENEA_ADAPTER}" &&
          -x "${ATENEA_ADAPTER}" && "$(stat -c %u "${ATENEA_ADAPTER}")" == 0 ]] ||
        fail "TOOLCHAIN_UNAVAILABLE" "The fixed Atenea runtime adapter is unavailable."
    fi
    [[ -S "${DOCKER_HOST_VALUE#unix://}" ||
        "${ATENEA_RUNTIME_FAKE_DOCKER:-0}" == "1" ]] ||
      fail "TOOLCHAIN_UNAVAILABLE" "The assigned rootless runtime slot is unavailable."
    return
  fi

  jq -e '
    (keys | sort) == [
      "allocatedPorts", "allocationPath", "logTail", "manifestPath",
      "operation", "projectId", "restrictions", "runtimeId",
      "runtimeNames", "schemaVersion", "sessionId"
    ] and
    .schemaVersion == 1 and
    (.operation |
      . == "status" or . == "doctor" or . == "build" or . == "up" or
      . == "stop" or . == "restart" or . == "redeploy" or . == "logs") and
    .restrictions == {
      noNewPrivileges: true,
      readOnlyRootFilesystem: true,
      dropAllCapabilities: true,
      hostNetwork: false,
      hostPid: false,
      hostIpc: false,
      devicesAllowed: false,
      daemonSocketsAllowed: false,
      mountsAllowed: []
    } and
    (.allocatedPorts | length == 1) and
    .allocatedPorts[0].name == "web" and
    .allocatedPorts[0].internalPort == 8080 and
    .allocatedPorts[0].protocol == "http" and
    .allocatedPorts[0].bindAddress == "127.0.0.1"
  ' "${PLAN}" >/dev/null ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "The runtime plan is incompatible."
  validate_fixture "${ALLOCATION}" "${MANIFEST}"
  jq -e \
    --arg session "${SESSION}" \
    --arg project "${PROJECT}" \
    --arg runtime "${RUNTIME}" '
      .sessionId == $session and .projectId == $project and
      .runtimeId == $runtime and
      (.slot | test("^slot[1-4]$")) and .state == "allocated"
    ' "${ALLOCATION}" >/dev/null ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "The plan does not own the assigned slot."
  SLOT="$(jq -r '.slot' "${ALLOCATION}")"
  RUNTIME_ROOT="$(jq -r '.runtimeRoot' "${ALLOCATION}")"
  LOGS_PATH="$(jq -r '.logsPath' "${ALLOCATION}")"
  ARTIFACTS_ROOT="$(jq -r '.artifactsRoot' "${ALLOCATION}")"
  LOOPBACK_PORT="$(jq -r '.allocatedPorts[0].loopbackPort' "${ALLOCATION}")"
  NETWORK="$(jq -r '.runtimeNames.network' "${ALLOCATION}")"
  COMPOSE_PROJECT="$(jq -r '.runtimeNames.composeProject' "${ALLOCATION}")"
  KIND="$(jq -r '.runtime.kind' "${MANIFEST}")"
  CONTAINER="${RUNTIME}-$([[ "${KIND}" == "compose" ]] && printf app || printf tomcat)"
  IMAGE="${RUNTIME}-fixture-${KIND}:task-4.3"
  ENGINE_ROOT="${RUNTIME_ROOT}/engine-v1"
  LOCK_PATH="${RUNTIME_ROOT}/engine-v1.lock"
  for path in "${RUNTIME_ROOT}" "${LOGS_PATH}" "${ARTIFACTS_ROOT}"; do
    [[ -d "${path}" && ! -L "${path}" ]] ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "A session-derived runtime path is unsafe."
  done
  if [[ -e "${ENGINE_ROOT}" || -L "${ENGINE_ROOT}" ]]; then
    [[ -d "${ENGINE_ROOT}" && ! -L "${ENGINE_ROOT}" &&
        "$(stat -c %u "${ENGINE_ROOT}")" == "$(id -u)" &&
        "$(stat -c %a "${ENGINE_ROOT}")" == "700" &&
        -f "${ENGINE_ROOT}/.owner-v1" &&
        "$(cat "${ENGINE_ROOT}/.owner-v1")" == "${SESSION} ${RUNTIME}" ]] ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "The engine state root has foreign ownership."
  else
    install -d -m 0700 "${ENGINE_ROOT}"
    chmod g-s,u=rwx,go= "${ENGINE_ROOT}"
    printf '%s %s\n' "${SESSION}" "${RUNTIME}" >"${ENGINE_ROOT}/.owner-v1"
  fi
  [[ ! -L "${LOCK_PATH}" ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "The runtime lock is unsafe."
  exec {engine_lock_fd}>"${LOCK_PATH}"
  [[ "$(stat -c %u "${LOCK_PATH}")" == "$(id -u)" ]] ||
    fail "RUNTIME_OWNERSHIP_CONFLICT" "The runtime lock has foreign ownership."
  flock -w 30 "${engine_lock_fd}" ||
    fail "RECONCILIATION_REQUIRED" "The session runtime is busy."

  if [[ "${TEST_MODE}" == "1" ]]; then
    DOCKER_HOST_VALUE="${ATENEA_RUNTIME_DOCKER_HOST:-}"
    [[ "${DOCKER_HOST_VALUE}" == unix:///tmp/* ||
        "${DOCKER_HOST_VALUE}" =~ ^unix:///run/atenea-runtime/slot[1-4]/docker\.sock$ ]] ||
      fail "RUNTIME_OWNERSHIP_CONFLICT" "Synthetic Docker host is outside the allowed slot."
  else
    DOCKER_HOST_VALUE="unix:///run/atenea-runtime/${SLOT}/docker.sock"
  fi
  [[ -S "${DOCKER_HOST_VALUE#unix://}" ||
      "${ATENEA_RUNTIME_FAKE_DOCKER:-0}" == "1" ]] ||
    fail "TOOLCHAIN_UNAVAILABLE" "The assigned rootless runtime slot is unavailable."
}

execute_plan() {
  PLAN="$(argument --plan "$@")" || usage
  for item in "$@"; do
    [[ "${item}" == "--json" ]] && JSON_MODE=true
  done
  if [[ "${JSON_MODE}" == "true" ]]; then
    [[ "$#" -eq 3 ]] || usage
  else
    [[ "$#" -eq 2 ]] || usage
  fi
  validate_plan
  operation="$(jq -r '.operation' "${PLAN}")"
  if [[ "${PROJECT}" == "atenea" ]]; then
    adapter_args=(
      execute
      --plan "${PLAN}"
      --docker-host "${DOCKER_HOST_VALUE}"
    )
    [[ "${JSON_MODE}" == "true" ]] && adapter_args+=(--json)
    exec "${ATENEA_ADAPTER}" "${adapter_args[@]}"
  fi
  case "${operation}" in
    doctor)
      docker_cmd version >/dev/null 2>&1 ||
        fail "TOOLCHAIN_UNAVAILABLE" "The assigned rootless slot is unhealthy."
      emit_result ready healthy "Synthetic runtime engine and assigned slot are ready."
      ;;
    build)
      build_fixture
      emit_result ready unknown "Synthetic fixture build completed."
      ;;
    up)
      start_fixture
      health_fixture ||
        fail "HEALTH_CHECK_FAILED" "The synthetic runtime did not become healthy."
      emit_result ready healthy "Synthetic runtime is healthy."
      ;;
    status)
      if container_exists; then
        assert_container_owned
        if health_fixture; then
          emit_result ready healthy
        elif [[ "$(docker_cmd container inspect --format '{{.State.Running}}' "${CONTAINER}")" == "true" ]]; then
          emit_result running unhealthy
        else
          emit_result stopped stopped
        fi
      else
        emit_result stopped stopped
      fi
      ;;
    logs)
      message="$(retain_logs)"
      if container_exists &&
          [[ "$(docker_cmd container inspect --format '{{.State.Running}}' "${CONTAINER}")" == "true" ]]; then
        emit_result ready unknown "${message}"
      else
        emit_result stopped stopped "${message}"
      fi
      ;;
    stop)
      stop_fixture
      retain_logs >/dev/null
      emit_result stopped stopped "Synthetic runtime stopped; session records and artifacts were preserved."
      ;;
    restart)
      if container_exists; then
        assert_container_owned
        docker_cmd restart --time 10 "${CONTAINER}" >/dev/null
      else
        start_fixture
      fi
      health_fixture ||
        fail "HEALTH_CHECK_FAILED" "The restarted synthetic runtime is unhealthy."
      emit_result ready healthy "Synthetic runtime restarted."
      ;;
    redeploy)
      stop_fixture
      remove_container_for_redeploy
      build_fixture
      start_fixture
      health_fixture ||
        fail "HEALTH_CHECK_FAILED" "The redeployed synthetic runtime is unhealthy."
      emit_result ready healthy "Synthetic runtime redeployed."
      ;;
  esac
}

case "${ACTION}" in
  inspect)
    inspect_fixture "$@"
    ;;
  execute)
    execute_plan "$@"
    ;;
  *)
    usage
    ;;
esac
