FROM eclipse-temurin:21-jdk AS jdk

FROM node:20-bookworm-slim

ARG CODEX_CLI_VERSION=0.130.0
ARG DOCKER_COMPOSE_VERSION=2.29.7

COPY --from=jdk /opt/java/openjdk /opt/java/openjdk

ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bubblewrap \
        ca-certificates \
        curl \
        docker.io \
        docker-compose \
        gh \
        git \
        iproute2 \
        jq \
        less \
        lsof \
        maven \
        openssh-client \
        postgresql-client \
        procps \
        rsync \
    && npm install -g @openai/codex@${CODEX_CLI_VERSION} \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /usr/local/lib/docker/cli-plugins \
    && curl -fsSL \
        "https://github.com/docker/compose/releases/download/v${DOCKER_COMPOSE_VERSION}/docker-compose-linux-x86_64" \
        -o /usr/local/lib/docker/cli-plugins/docker-compose \
    && chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

RUN mkdir -p /workspace/codex-home /workspace/repos \
    && git config --system --add safe.directory '*'

ENV HOME=/workspace/codex-home

WORKDIR /srv/atenea

EXPOSE 8092

CMD ["codex", "app-server", "--listen", "ws://0.0.0.0:8092", "-c", "approval_policy=\"never\"", "-c", "sandbox_mode=\"danger-full-access\"", "-c", "shell_environment_policy.inherit=\"all\"", "-c", "projects.\"/srv/atenea\".trust_level=\"trusted\"", "-c", "projects.\"/workspace/repos\".trust_level=\"trusted\""]
