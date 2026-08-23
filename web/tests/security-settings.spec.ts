import { expect, Page, Route, test } from "@playwright/test";

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

const authSession = {
  accessToken: "synthetic-access",
  accessTokenExpiresAt: "2099-01-01T00:00:00Z",
  operator: {
    id: 1,
    email: "operator@example.invalid",
    displayName: "Operador sintético",
    codexOperationsRole: "PLATFORM_ADMINISTRATOR"
  }
};

for (const viewport of [
  { name: "desktop", width: 1440, height: 900 },
  { name: "mobile", width: 390, height: 844 }
] as const) {
  test(`${viewport.name} security settings exposes exact session state and remote revoke`, async ({ page }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    let refreshCalls = 0;
    let revoked = false;
    await page.route("**/api/**", async (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      if (path === "/api/web/auth/refresh") {
        refreshCalls += 1;
        expect(request.headers()["x-atenea-session-protocol"]).toBe("FAMILY_V1");
        expect(request.headers()["x-atenea-single-flight"]).toBe("true");
        return json(route, authSession);
      }
      if (path === "/api/mobile/auth/sessions" && request.method() === "GET") {
        return json(route, [
          {
            familyId: "00000000-0000-4000-8000-000000000001",
            clientType: "WEB",
            deviceLabel: "Navegador principal",
            createdAt: "2026-08-13T09:00:00Z",
            lastUsedAt: "2026-08-13T10:00:00Z",
            absoluteExpiresAt: "2026-09-13T09:00:00Z",
            state: "ACTIVE",
            current: true
          },
          ...(revoked ? [] : [{
            familyId: "00000000-0000-4000-8000-000000000002",
            clientType: "ANDROID",
            deviceLabel: "Teléfono de pruebas",
            createdAt: "2026-08-12T09:00:00Z",
            lastUsedAt: "2026-08-13T09:58:00Z",
            absoluteExpiresAt: "2026-09-12T09:00:00Z",
            state: "ACTIVE",
            current: false
          }])
        ]);
      }
      if (path.endsWith("000000000002") && request.method() === "DELETE") {
        revoked = true;
        return route.fulfill({ status: 204, body: "" });
      }
      if (path === "/api/auth/totp/enrollments" && request.method() === "POST") {
        return json(route, {
          enrollmentId: "00000000-0000-4000-8000-000000000010",
          secret: "SYNTHETICFIXTUREONLY",
          expiresAt: "2099-01-01T00:10:00Z"
        });
      }
      if (path === "/api/auth/totp/enrollments/activate" && request.method() === "POST") {
        return json(route, { recoveryCodes: Array.from({ length: 10 }, (_, index) => `fixture-code-${index + 1}`) });
      }
      if (path === "/actuator/health") return json(route, { status: "UP" });
      if (path === "/api/mobile/operations/hosts") return json(route, []);
      if (path === "/api/mobile/operations/incidents") return json(route, { incidents: [] });
      return json(route, {});
    });

    await page.goto("/#/settings", { waitUntil: "networkidle" });
    await expect(page.getByRole("heading", { name: "Seguridad de la cuenta" })).toBeVisible();
    await expect(page.getByText("Navegador principal · Esta sesión", { exact: true })).toBeVisible();
    await expect(page.getByText("Teléfono de pruebas", { exact: true })).toBeVisible();
    await expect(page.getByText("Refresh en cookie HttpOnly; acceso sólo en memoria y refresh coordinado.")).toBeVisible();
    expect(refreshCalls).toBe(1);
    expect(await page.evaluate(() => JSON.stringify(sessionStorage))).not.toContain("synthetic-access");
    await expectNoHorizontalOverflow(page);
    await retainScreenshot(page, `${viewport.name}-security-settings.png`);

    await page.getByRole("button", { name: "Revocar", exact: true }).click();
    await expect(page.getByText("Teléfono de pruebas", { exact: true })).toHaveCount(0);
    expect(revoked).toBe(true);

    await page.getByRole("button", { name: "Preparar TOTP" }).click();
    await expect(page.getByText("Alta TOTP pendiente", { exact: true })).toBeVisible();
    await expect(page.getByText("SYNTHETICFIXTUREONLY", { exact: true })).toBeVisible();
    await page.getByLabel("Código TOTP").fill("123456");
    await page.getByRole("button", { name: "Activar TOTP" }).click();
    await expect(page.getByText("fixture-code-10", { exact: true })).toBeVisible();
    await page.getByText("Códigos de recuperación", { exact: true }).scrollIntoViewIfNeeded();
    await retainScreenshot(page, `${viewport.name}-security-factors.png`);
  });
}

async function expectNoHorizontalOverflow(page: Page) {
  const widths = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    document: document.documentElement.scrollWidth
  }));
  expect(widths.document).toBeLessThanOrEqual(widths.viewport);
}

async function retainScreenshot(page: Page, name: string) {
  const directory = process.env.ATENEA_VISUAL_EVIDENCE_DIR;
  if (directory) await page.screenshot({ path: `${directory}/${name}`, fullPage: false });
}
