FROM node:20-bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends bubblewrap ca-certificates \
    && npm install -g @openai/codex@0.116.0 \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /workspace/codex-home \
    && chown -R node:node /workspace

ENV HOME=/workspace/codex-home

WORKDIR /workspace/repos/internal/atenea

USER node

CMD ["codex", "app-server", "--listen", "ws://0.0.0.0:8092", "-c", "projects.\"/workspace/repos/internal/atenea\".trust_level=\"trusted\""]
