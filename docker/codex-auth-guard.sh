#!/usr/bin/env sh
set -eu

required="${ATENEA_CODEX_REQUIRED_AUTH_MODE:-chatgpt}"
auth_file="${CODEX_AUTH_FILE:-$HOME/.codex/auth.json}"
status_file="${ATENEA_CODEX_AUTH_STATUS_FILE:-$HOME/codex-auth-status.json}"

if [ "$required" = "disabled" ]; then
  node - "$status_file" "$required" <<'NODE'
const fs = require("fs");
const path = require("path");
const statusFile = process.argv[2];
const required = process.argv[3];
fs.mkdirSync(path.dirname(statusFile), { recursive: true });
fs.writeFileSync(statusFile, JSON.stringify({
  checkedAt: new Date().toISOString(),
  compliant: true,
  status: "guard_disabled",
  requiredAuthMode: required,
  authMode: null,
  apiKeyPresent: false,
  tokensPresent: false
}) + "\n", { mode: 0o644 });
NODE
  exec "$@"
fi

if [ ! -f "$auth_file" ]; then
  echo "BLOCKED: Codex auth file not found: $auth_file" >&2
  exit 42
fi

node - "$auth_file" "$required" "$status_file" <<'NODE'
const fs = require("fs");
const path = require("path");

const authFile = process.argv[2];
const required = process.argv[3];
const statusFile = process.argv[4];
const raw = fs.readFileSync(authFile, "utf8");
const auth = JSON.parse(raw);
const mode = String(auth.auth_mode || "");
const apiKeyPresent = typeof auth.OPENAI_API_KEY === "string" && auth.OPENAI_API_KEY.trim().length > 0;
const tokensPresent = Boolean(auth.tokens && auth.tokens.access_token && auth.tokens.refresh_token);

function writeStatus(compliant, status) {
  fs.mkdirSync(path.dirname(statusFile), { recursive: true });
  fs.writeFileSync(statusFile, JSON.stringify({
    checkedAt: new Date().toISOString(),
    compliant,
    status,
    requiredAuthMode: required,
    authMode: mode || null,
    apiKeyPresent,
    tokensPresent
  }) + "\n", { mode: 0o644 });
  fs.chmodSync(statusFile, 0o644);
}

if (mode !== required) {
  writeStatus(false, "auth_mode_mismatch");
  console.error(`BLOCKED: Codex App Server must use ${required} auth, found ${mode || "missing"}.`);
  process.exit(42);
}
if (required === "chatgpt" && apiKeyPresent) {
  writeStatus(false, "api_key_present");
  console.error("BLOCKED: Codex App Server is configured with OPENAI_API_KEY while ChatGPT auth is required.");
  process.exit(42);
}
if (required === "chatgpt" && !tokensPresent) {
  writeStatus(false, "tokens_missing");
  console.error("BLOCKED: Codex App Server ChatGPT tokens are missing or incomplete.");
  process.exit(42);
}
writeStatus(true, "ok");
console.error(`Codex auth guard OK: ${mode}.`);
NODE

exec "$@"
