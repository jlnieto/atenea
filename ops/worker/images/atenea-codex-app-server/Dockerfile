FROM node:22.16.0-bookworm-slim@sha256:048ed02c5fd52e86fda6fbd2f6a76cf0d4492fd6c6fee9e2c463ed5108da0e34

RUN npm install --global --omit=dev @openai/codex@0.145.0 \
    && codex --version

COPY --chown=root:root --chmod=0555 codex-loopback-proxy.mjs \
    /usr/local/lib/atenea/codex-loopback-proxy.mjs

LABEL com.atenea.image="codex-app-server" \
      com.atenea.codex.version="0.145.0" \
      com.atenea.node.version="22.16.0" \
      com.atenea.codex.auth-boundary="loopback-proxy-v1"

USER node
