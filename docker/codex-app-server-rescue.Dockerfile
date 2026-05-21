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
        chromium \
        curl \
        docker.io \
        docker-compose \
        fonts-liberation \
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
        xvfb \
    && npm install -g @openai/codex@${CODEX_CLI_VERSION} \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /usr/local/lib/docker/cli-plugins \
    && curl -fsSL \
        "https://github.com/docker/compose/releases/download/v${DOCKER_COMPOSE_VERSION}/docker-compose-linux-x86_64" \
        -o /usr/local/lib/docker/cli-plugins/docker-compose \
    && chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

RUN mkdir -p /workspace/codex-home /workspace/repos \
    && git config --system --add safe.directory '*' \
    && git config --system user.name "Atenea" \
    && git config --system user.email "atenea@yudri.es"

COPY docker/codex-auth-guard.sh /usr/local/bin/codex-auth-guard
RUN chmod +x /usr/local/bin/codex-auth-guard

ENV HOME=/workspace/codex-home
ENV CHROME_BIN=/usr/bin/chromium
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium
ENV PUPPETEER_CACHE_DIR=/workspace/codex-home/puppeteer
ENV PLAYWRIGHT_BROWSERS_PATH=/workspace/codex-home/playwright-browsers

WORKDIR /srv/atenea

EXPOSE 8092

CMD ["sh", "-lc", "umask 0002 && exec sh /usr/local/bin/codex-auth-guard codex app-server --listen ws://0.0.0.0:8092 -c 'approval_policy=\"never\"' -c 'sandbox_mode=\"danger-full-access\"' -c 'shell_environment_policy.inherit=\"all\"' -c 'projects.\"/srv/atenea\".trust_level=\"trusted\"' -c 'projects.\"/workspace/repos\".trust_level=\"trusted\"'"]
