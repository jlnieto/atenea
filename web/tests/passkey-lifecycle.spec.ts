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

const inventory = {
  state: "READY",
  credentials: [
    {
      recordId: "00000000-0000-4000-8000-000000000201",
      label: "Google Password Manager · 1",
      providerCategory: "GOOGLE_PASSWORD_MANAGER",
      provenance: "OPERATOR_DECLARED",
      backupEligible: true,
      backupState: true,
      transports: ["internal"],
      createdAt: "2026-08-14T09:00:00Z",
      lastUsedAt: null,
      lastVerifiedAt: "2026-08-14T10:00:00Z",
      state: "ACTIVE"
    },
    {
      recordId: "00000000-0000-4000-8000-000000000202",
      label: "1Password · 2",
      providerCategory: "ONE_PASSWORD",
      provenance: "OPERATOR_DECLARED",
      backupEligible: true,
      backupState: true,
      transports: ["hybrid"],
      createdAt: "2026-08-14T09:05:00Z",
      lastUsedAt: null,
      lastVerifiedAt: "2026-08-14T10:05:00Z",
      state: "ACTIVE"
    }
  ],
  requiredProviderDomains: ["GOOGLE_PASSWORD_MANAGER", "ONE_PASSWORD"],
  verifiedProviderDomains: ["GOOGLE_PASSWORD_MANAGER", "ONE_PASSWORD"],
  independentDomainsReady: true,
  signallingEnabled: true,
  readOnly: false,
  nextAction: "Los dos dominios independientes están verificados."
};

const completeSnapshot = {
  relyingPartyId: "127.0.0.1",
  userId: "AQ",
  allAcceptedCredentialIds: ["Ag", "Aw"],
  activeCredentialCount: 2,
  credentialVersion: 7
};

const disabledPasskeyReset = {
  state: "DISABLED",
  targetProvider: "1Password",
  expectedHistoricalCredentialCount: 4,
  observedHistoricalCredentialCount: null,
  candidateRecordId: null,
  candidateLabel: null,
  activeTotpCount: null,
  activeRecoveryCodeCount: null,
  nextAction: "El reinicio controlado de passkeys permanece desactivado."
};

test("Signal API receives only the exact complete active snapshot", async ({ page }) => {
  await installSignalMethod(page, "success");
  await routeSettings(page, completeSnapshot);
  await page.goto("/#/settings", { waitUntil: "networkidle" });

  await page.getByRole("button", { name: "Sincronizar inventario activo" }).click();

  await expect(page.getByText("El proveedor recibió el inventario activo completo.", { exact: true })).toBeVisible();
  const signalled = await page.evaluate(() => JSON.parse(sessionStorage.getItem("synthetic-signal") || "null"));
  expect(signalled).toEqual({
    rpId: "127.0.0.1",
    userId: "AQ",
    allAcceptedCredentialIds: ["Ag", "Aw"]
  });
});

test("missing Signal API produces a clear manual fallback", async ({ page }) => {
  await installSignalMethod(page, "missing");
  await routeSettings(page, completeSnapshot);
  await page.goto("/#/settings", { waitUntil: "networkidle" });

  await page.getByRole("button", { name: "Sincronizar inventario activo" }).click();

  await expect(page.getByText(
    "Este navegador no admite Signal API. Revisa las passkeys manualmente en el proveedor.",
    { exact: true }
  )).toBeVisible();
});

test("Signal API failure stays local and exposes an actionable fallback", async ({ page }) => {
  await installSignalMethod(page, "failure");
  await routeSettings(page, completeSnapshot);
  await page.goto("/#/settings", { waitUntil: "networkidle" });

  await page.getByRole("button", { name: "Sincronizar inventario activo" }).click();

  await expect(page.getByText("No se pudo sincronizar con el proveedor sintético.", { exact: true })).toBeVisible();
  await expect(page.getByText("Google Password Manager · 1", { exact: true })).toBeVisible();
  await expect(page.getByText("1Password · 2", { exact: true })).toBeVisible();
});

test("an incomplete snapshot is a grave error and is never signalled", async ({ page }) => {
  await installSignalMethod(page, "success");
  await routeSettings(page, { ...completeSnapshot, activeCredentialCount: 3 });
  await page.goto("/#/settings", { waitUntil: "networkidle" });

  await page.getByRole("button", { name: "Sincronizar inventario activo" }).click();

  await expect(page.getByText(
    "El snapshot de passkeys no es completo; no se enviará ninguna señal.",
    { exact: true }
  )).toBeVisible();
  expect(await page.evaluate(() => sessionStorage.getItem("synthetic-signal"))).toBeNull();
});

async function routeSettings(page: Page, snapshot: typeof completeSnapshot) {
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/web/auth/refresh") return json(route, authSession);
    if (path === "/api/mobile/auth/sessions") return json(route, []);
    if (path === "/api/auth/webauthn/credentials") return json(route, inventory);
    if (path === "/api/auth/webauthn/passkey-reset") return json(route, disabledPasskeyReset);
    if (path === "/api/auth/webauthn/credentials/signal-snapshot") return json(route, snapshot);
    if (path === "/actuator/health") return json(route, { status: "UP" });
    if (path === "/api/mobile/operations/hosts") return json(route, []);
    if (path === "/api/mobile/operations/incidents") return json(route, { incidents: [] });
    return json(route, {});
  });
}

async function installSignalMethod(page: Page, mode: "success" | "missing" | "failure") {
  await page.addInitScript((selectedMode) => {
    class SyntheticPublicKeyCredential {}
    if (selectedMode === "success") {
      Object.assign(SyntheticPublicKeyCredential, {
        signalAllAcceptedCredentials: async (options: unknown) => {
          sessionStorage.setItem("synthetic-signal", JSON.stringify(options));
        }
      });
    }
    if (selectedMode === "failure") {
      Object.assign(SyntheticPublicKeyCredential, {
        signalAllAcceptedCredentials: async () => {
          throw new Error("No se pudo sincronizar con el proveedor sintético.");
        }
      });
    }
    Object.defineProperty(window, "PublicKeyCredential", {
      configurable: true,
      value: SyntheticPublicKeyCredential
    });
  }, mode);
}
