# syntax=docker/dockerfile:1.6

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./

RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -q -DskipTests dependency:go-offline

COPY src src

RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -q -DskipTests clean package

FROM eclipse-temurin:21-jre
WORKDIR /app

ARG CODEX_CLI_VERSION=0.130.0
ARG DOCKER_COMPOSE_VERSION=v2.29.7

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl docker.io git mariadb-client nodejs npm openssh-client postgresql-client \
    && mkdir -p /usr/local/lib/docker/cli-plugins \
    && curl -fsSL "https://github.com/docker/compose/releases/download/${DOCKER_COMPOSE_VERSION}/docker-compose-linux-x86_64" \
        -o /usr/local/lib/docker/cli-plugins/docker-compose \
    && chmod +x /usr/local/lib/docker/cli-plugins/docker-compose \
    && npm install -g @openai/codex@${CODEX_CLI_VERSION} \
    && rm -rf /var/lib/apt/lists/*

RUN git config --system --add safe.directory '*'

RUN groupadd -r -g 1001 appuser \
    && useradd -r -u 1001 -g appuser -m -d /home/appuser appuser

COPY --from=build /workspace/target/atenea-0.0.1-SNAPSHOT.jar app.jar

USER appuser

EXPOSE 8081

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "app.jar"]
