FROM node:20-bookworm-slim

ARG CODEX_CLI_VERSION=0.130.0

RUN apt-get update \
    && apt-get install -y --no-install-recommends bubblewrap ca-certificates git openssh-client \
    && npm install -g @openai/codex@${CODEX_CLI_VERSION} \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /workspace/codex-home \
    && chown -R node:node /workspace

ENV HOME=/workspace/codex-home

WORKDIR /workspace/repos/internal/atenea

USER node

CMD ["codex", "app-server", "--listen", "ws://0.0.0.0:8092", "-c", "projects.\"/workspace/repos/internal/atenea\".trust_level=\"trusted\""]
