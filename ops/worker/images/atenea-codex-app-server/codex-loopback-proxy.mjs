import net from "node:net";
import process from "node:process";
import { spawn } from "node:child_process";

const publicHost = "0.0.0.0";
const publicPort = 8092;
const privateHost = "127.0.0.1";
const privatePort = 18092;

const codex = spawn(
  "codex",
  [
    "app-server",
    "--listen",
    `ws://${privateHost}:${privatePort}`,
    ...process.argv.slice(2),
  ],
  { stdio: "inherit" },
);

const proxy = net.createServer((client) => {
  const upstream = net.createConnection({
    host: privateHost,
    port: privatePort,
  });
  client.pipe(upstream);
  upstream.pipe(client);
  const closeBoth = () => {
    client.destroy();
    upstream.destroy();
  };
  client.on("error", closeBoth);
  upstream.on("error", closeBoth);
});

const terminate = (signal) => {
  proxy.close();
  if (!codex.killed) {
    codex.kill(signal);
  }
};

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => terminate(signal));
}

proxy.on("error", (error) => {
  console.error(`Codex loopback proxy failed: ${error.message}`);
  terminate("SIGTERM");
  process.exitCode = 1;
});

codex.on("error", (error) => {
  console.error(`Codex App Server failed: ${error.message}`);
  proxy.close();
  process.exitCode = 1;
});

codex.on("exit", (code, signal) => {
  proxy.close(() => {
    process.exit(code ?? (signal ? 1 : 0));
  });
});

proxy.listen(publicPort, publicHost, () => {
  console.error(
    `Codex development proxy listening on ${publicHost}:${publicPort}; ` +
      `unauthenticated App Server remains loopback-only on ${privateHost}:${privatePort}`,
  );
});
