"use strict";

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { chromium } = require(
  process.env.ATENEA_PLAYWRIGHT_MODULE || "playwright"
);

function fail(message) {
  throw new Error(message);
}

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

async function main() {
  const [casesPath, outputRoot] = process.argv.slice(2);
  if (!casesPath || !outputRoot || !path.resolve(casesPath).startsWith("/tmp/") ||
      !path.resolve(outputRoot).startsWith("/tmp/")) {
    fail("Browser cases and output root must be explicit paths beneath /tmp.");
  }
  const cases = JSON.parse(fs.readFileSync(casesPath, "utf8"));
  if (!Array.isArray(cases) || cases.length < 1 || cases.length > 4) {
    fail("One to four bounded Compose/Tomcat browser cases are required.");
  }
  fs.mkdirSync(outputRoot, { recursive: true, mode: 0o700 });
  const browser = await chromium.launch({ headless: true, timeout: 30000 });
  const records = [];
  try {
    for (const item of cases) {
      const parsed = new URL(item.url);
      if (parsed.protocol !== "http:" || parsed.hostname !== "127.0.0.1" ||
          parsed.port !== String(item.loopbackPort) ||
          parsed.pathname !== item.route ||
          !/^[0-9a-f-]{36}$/.test(item.sessionId) ||
          !/^[a-z0-9-]+$/.test(item.runId)) {
        fail(`Case identity or loopback URL is invalid for ${item.name}.`);
      }
      const context = await browser.newContext({
        viewport: { width: item.width, height: item.height }
      });
      const page = await context.newPage();
      page.setDefaultTimeout(10000);
      page.setDefaultNavigationTimeout(15000);
      try {
        const navigationUrl = new URL(item.url);
        if (item.connectHost) {
          const allowed = (process.env.ATENEA_BROWSER_ALLOWED_RUNTIME_HOSTS || "")
            .split(",").filter(Boolean);
          if (!allowed.includes(item.connectHost)) {
            fail(`Runtime transport host is not allowlisted for ${item.name}.`);
          }
          navigationUrl.hostname = item.connectHost;
          navigationUrl.port = "8080";
        } else if (process.env.ATENEA_BROWSER_CONNECT_HOST) {
          navigationUrl.hostname = process.env.ATENEA_BROWSER_CONNECT_HOST;
        }
        const response = await page.goto(navigationUrl.toString(), {
          waitUntil: "networkidle",
          timeout: 15000
        });
        if (!response || !response.ok()) fail(`${item.name} did not return HTTP success.`);
        const body = page.locator("body");
        await body.waitFor({ state: "visible", timeout: 10000 });
        const text = (await body.innerText()).trim();
        for (const expected of item.expectedText) {
          if (!text.includes(expected)) fail(`${item.name} omitted ${expected}.`);
        }
        const visual = await page.evaluate(() => {
          const root = document.documentElement;
          const body = document.body;
          const bodyRect = body.getBoundingClientRect();
          return {
            textLength: (body.innerText || "").trim().length,
            horizontalOverflow:
              root.scrollWidth > root.clientWidth || body.scrollWidth > body.clientWidth,
            clipped:
              bodyRect.left < -1 || bodyRect.right > root.clientWidth + 1 ||
              bodyRect.top < -1,
            viewportWidth: root.clientWidth,
            viewportHeight: root.clientHeight
          };
        });
        if (visual.textLength === 0 || visual.horizontalOverflow || visual.clipped) {
          fail(`${item.name} is empty, clipped or horizontally overflowing.`);
        }
        const artifactDir = path.join(
          outputRoot, item.sessionId, "runs", item.runId, "browser"
        );
        fs.mkdirSync(artifactDir, { recursive: true, mode: 0o700 });
        const screenshot = path.join(artifactDir, `${item.name}.png`);
        await page.screenshot({ path: screenshot, fullPage: false, timeout: 15000 });
        records.push({
          schemaVersion: 1,
          sessionId: item.sessionId,
          runId: item.runId,
          source: "playwright",
          name: item.name,
          url: item.url,
          route: item.route,
          viewport: `${item.width}x${item.height}`,
          contentType: "image/png",
          retention: "session",
          path: screenshot,
          sha256: sha256(screenshot),
          visual
        });
      } finally {
        await page.close();
        await context.close();
      }
    }
  } finally {
    await browser.close();
  }
  const registry = path.join(outputRoot, "browser-artifacts-v1.json");
  const previous = fs.existsSync(registry)
    ? JSON.parse(fs.readFileSync(registry, "utf8")) : [];
  const keys = new Set(records.map((record) =>
    `${record.sessionId}:${record.runId}:${record.name}`));
  const merged = previous.filter((record) =>
    !keys.has(`${record.sessionId}:${record.runId}:${record.name}`));
  merged.push(...records);
  merged.sort((a, b) => a.name.localeCompare(b.name));
  fs.writeFileSync(registry, `${JSON.stringify(merged, null, 2)}\n`, { mode: 0o600 });
  process.stdout.write(`${registry}\n`);
}

main().catch((error) => {
  process.stderr.write(`BROWSER_CHECK_FAILED: ${error.message}\n`);
  process.exitCode = 1;
});
