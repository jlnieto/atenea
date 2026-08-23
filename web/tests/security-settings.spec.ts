import { expect, Locator, Page, Route, test } from "@playwright/test";

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

const syntheticPasskeyInventory = {
  state: "READY",
  credentials: [
    {
      recordId: "00000000-0000-4000-8000-000000000101",
      label: "Google Password Manager · 1",
      providerCategory: "GOOGLE_PASSWORD_MANAGER",
      provenance: "OPERATOR_DECLARED",
      backupEligible: true,
      backupState: true,
      transports: ["internal"],
      createdAt: "2026-08-14T09:00:00Z",
      lastUsedAt: "2026-08-14T10:00:00Z",
      lastVerifiedAt: "2026-08-14T10:00:00Z",
      state: "ACTIVE"
    },
    {
      recordId: "00000000-0000-4000-8000-000000000102",
      label: "1Password · 2",
      providerCategory: "ONE_PASSWORD",
      provenance: "OPERATOR_DECLARED",
      backupEligible: true,
      backupState: true,
      transports: ["hybrid", "internal"],
      createdAt: "2026-08-14T09:05:00Z",
      lastUsedAt: "2026-08-14T10:05:00Z",
      lastVerifiedAt: "2026-08-14T10:05:00Z",
      state: "ACTIVE"
    },
    {
      recordId: "00000000-0000-4000-8000-000000000103",
      label: "Proveedor desconocido · 3",
      providerCategory: "UNKNOWN",
      provenance: "UNKNOWN",
      backupEligible: false,
      backupState: false,
      transports: [],
      createdAt: "2026-08-14T09:10:00Z",
      lastUsedAt: null,
      lastVerifiedAt: null,
      state: "REVOKED"
    }
  ],
  requiredProviderDomains: ["GOOGLE_PASSWORD_MANAGER", "ONE_PASSWORD"],
  verifiedProviderDomains: ["GOOGLE_PASSWORD_MANAGER", "ONE_PASSWORD"],
  independentDomainsReady: true,
  signallingEnabled: true,
  readOnly: false,
  nextAction: "Los dos dominios independientes están verificados."
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
      if (path === "/api/auth/webauthn/credentials" && request.method() === "GET") {
        return json(route, syntheticPasskeyInventory);
      }
      if (path === "/api/auth/webauthn/passkey-reset" && request.method() === "GET") {
        return json(route, disabledPasskeyReset);
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
    await expect(page.getByText("Passkeys independientes verificadas", { exact: true })).toBeVisible();
    await expect(page.getByText("2 dominios", { exact: true })).toBeVisible();
    await expect(page.getByText("Google Password Manager · 1", { exact: true })).toBeVisible();
    await expect(page.getByText("1Password · 2", { exact: true })).toBeVisible();
    await expect(page.getByText("Proveedor desconocido · 3", { exact: true })).toBeVisible();
    await expect(page.locator("body")).not.toContainText("credentialId");
    await expect(page.locator("body")).not.toContainText("AAGUID");
    expect(refreshCalls).toBe(1);
    expect(await page.evaluate(() => JSON.stringify(sessionStorage))).not.toContain("synthetic-access");
    await expectNoHorizontalOverflow(page);
    await retainScreenshot(page, `${viewport.name}-security-settings.png`);

    await page.locator(".passkey-state").evaluate((element) => {
      const top = element.getBoundingClientRect().top + window.scrollY - 84;
      window.scrollTo({ top, behavior: "auto" });
    });
    await expect(page.getByRole("button", { name: "Registrar passkey" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Verificar passkey seleccionada" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Revocar en Atenea" }).first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Sincronizar inventario activo" })).toBeVisible();
    await retainScreenshot(page, `${viewport.name}-passkey-inventory.png`);

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

for (const viewport of [
  { name: "desktop", width: 1440, height: 900 },
  { name: "mobile", width: 390, height: 844 }
] as const) {
  test(`${viewport.name} discovery is targeted and exposes no factor mutation`, async ({ page }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    const targetRecordId = "00000000-0000-4000-8000-000000000101";
    let optionsTarget = "";
    let completedTarget = "";
    let forbiddenMutation = false;
    const discoveryInventory = {
      ...syntheticPasskeyInventory,
      state: "ACTION_REQUIRED",
      independentDomainsReady: false,
      verifiedProviderDomains: [],
      signallingEnabled: false,
      readOnly: true,
      nextAction: "Selecciona y verifica una sola passkey activa; no se permiten altas ni revocaciones."
    };
    await page.addInitScript(() => {
      const bytes = (values: number[]) => Uint8Array.from(values).buffer;
      Object.defineProperty(window, "PublicKeyCredential", {
        configurable: true,
        value: class SyntheticPublicKeyCredential {}
      });
      Object.defineProperty(navigator, "credentials", {
        configurable: true,
        value: {
          get: async () => ({
            rawId: bytes([1, 2, 3]),
            response: {
              userHandle: null,
              clientDataJSON: bytes([4, 5, 6]),
              authenticatorData: bytes([7, 8, 9]),
              signature: bytes([10, 11, 12])
            }
          })
        }
      });
    });
    await page.route("**/api/**", async (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      if (path === "/api/web/auth/refresh") return json(route, authSession);
      if (path === "/api/mobile/auth/sessions") return json(route, []);
      if (path === "/api/auth/webauthn/credentials" && request.method() === "GET") {
        return json(route, discoveryInventory);
      }
      if (path === "/api/auth/webauthn/passkey-reset" && request.method() === "GET") {
        return json(route, disabledPasskeyReset);
      }
      if (path === "/api/web/auth/webauthn/ownership/options") {
        optionsTarget = request.postDataJSON().recordId;
        return json(route, {
          requestId: "00000000-0000-4000-8000-000000000301",
          challenge: "BAUG",
          timeoutMillis: 30_000,
          relyingPartyId: "atenea.yudri.es",
          credentialParameters: [],
          credentials: [{ type: "public-key", id: "AQID", transports: ["internal"] }],
          userVerification: "required"
        });
      }
      if (path === "/api/web/auth/webauthn/ownership") {
        const body = request.postDataJSON();
        completedTarget = body.credentialId;
        expect(body.providerCategory).toBe("GOOGLE_PASSWORD_MANAGER");
        return json(route, {
          recordId: targetRecordId,
          label: "Google Password Manager · 1",
          providerCategory: "GOOGLE_PASSWORD_MANAGER",
          verifiedAt: "2026-08-15T12:00:00Z"
        });
      }
      if ((path.includes("/webauthn/registration")
          || path.includes("/totp/enrollments")
          || path.includes("signal-snapshot")
          || (path.startsWith("/api/auth/webauthn/credentials/") && request.method() === "DELETE"))) {
        forbiddenMutation = true;
        return json(route, {}, 409);
      }
      if (path === "/actuator/health") return json(route, { status: "UP" });
      if (path === "/api/mobile/operations/hosts") return json(route, []);
      if (path === "/api/mobile/operations/incidents") return json(route, { incidents: [] });
      return json(route, {});
    });

    await page.goto("/#/settings", { waitUntil: "networkidle" });
    await expect(page.getByText("Discovery de passkeys: solo lectura", { exact: true })).toBeVisible();
    await expect(page.getByText("Solo lectura", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Registrar passkey" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Revocar en Atenea" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Sincronizar inventario activo" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Preparar TOTP" })).toHaveCount(0);
    const verify = page.getByRole("button", { name: "Verificar passkey seleccionada" });
    await expect(verify).toBeVisible();
    await expect(verify).toBeDisabled();
    await expectInFirstViewport(page, page.locator(".passkey-state"));
    await expectInFirstViewport(page, verify);
    await retainScreenshot(page, `${viewport.name}-passkey-discovery-initial.png`);
    await page.getByRole("radio", { name: "Verificar Google Password Manager · 1" }).check();
    await page.getByLabel("Proveedor de passkey").selectOption("GOOGLE_PASSWORD_MANAGER");
    await expect(verify).toBeEnabled();
    await verify.click();
    await expect(page.getByText(
      "Google Password Manager · 1 verificada sin exponer su identificador.",
      { exact: true }
    )).toBeVisible();
    expect(optionsTarget).toBe(targetRecordId);
    expect(completedTarget).toBe("AQID");
    expect(forbiddenMutation).toBe(false);
    await expectNoHorizontalOverflow(page);
    await retainScreenshot(page, `${viewport.name}-passkey-discovery-read-only.png`);
  });
}

for (const viewport of [
  { name: "desktop", width: 1440, height: 900 },
  { name: "mobile", width: 390, height: 844 }
] as const) {
  test(`${viewport.name} controlled reset exposes one safe action at each stage`, async ({ page }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    type ResetStage = "REGISTER_NEW" | "PROVE_NEW" | "COMMIT_READY" | "COMPLETE";
    let resetStage: ResetStage = "REGISTER_NEW";
    const candidateRecordId = "00000000-0000-4000-8000-000000000105";
    let registrationProvider = "";
    let proofTarget = "";
    let proofProvider = "";
    let committedCandidate = "";
    await page.addInitScript(() => {
      const bytes = (values: number[]) => Uint8Array.from(values).buffer;
      Object.defineProperty(window, "PublicKeyCredential", {
        configurable: true,
        value: class SyntheticPublicKeyCredential {}
      });
      Object.defineProperty(navigator, "credentials", {
        configurable: true,
        value: {
          create: async () => ({
            rawId: bytes([1, 2, 3]),
            response: {
              clientDataJSON: bytes([4, 5, 6]),
              attestationObject: bytes([7, 8, 9]),
              getTransports: () => ["internal"]
            }
          }),
          get: async () => ({
            rawId: bytes([1, 2, 3]),
            response: {
              userHandle: null,
              clientDataJSON: bytes([4, 5, 6]),
              authenticatorData: bytes([7, 8, 9]),
              signature: bytes([10, 11, 12])
            }
          })
        }
      });
    });
    await page.route("**/api/**", async (route) => {
      const request = route.request();
      const path = new URL(request.url()).pathname;
      if (path === "/api/web/auth/refresh") return json(route, authSession);
      if (path === "/api/mobile/auth/sessions") return json(route, []);
      if (path === "/api/auth/webauthn/credentials" && request.method() === "GET") {
        const credentials = [
          ...syntheticPasskeyInventory.credentials,
          {
            ...syntheticPasskeyInventory.credentials[2],
            recordId: "00000000-0000-4000-8000-000000000104",
            label: "Proveedor desconocido · 4"
          }
        ];
        if (resetStage !== "REGISTER_NEW") {
          credentials.push({
            ...syntheticPasskeyInventory.credentials[1],
            recordId: candidateRecordId,
            label: "1Password · 5",
            lastVerifiedAt: resetStage === "PROVE_NEW" ? null : "2026-08-19T12:00:00Z",
            state: "ACTIVE"
          });
        }
        return json(route, {
          ...syntheticPasskeyInventory,
          state: "ACTION_REQUIRED",
          credentials,
          signallingEnabled: false,
          readOnly: true,
          nextAction: "Sigue el reinicio controlado."
        });
      }
      if (path === "/api/auth/webauthn/passkey-reset" && request.method() === "GET") {
        const nextAction = {
          REGISTER_NEW: "Registra una passkey nueva en 1Password.",
          PROVE_NEW: "Verifica la nueva passkey de 1Password.",
          COMMIT_READY: "Confirma la revocación de las cuatro passkeys históricas.",
          COMPLETE: "Reinicio completado con una passkey activa en 1Password."
        }[resetStage];
        return json(route, {
          state: resetStage,
          targetProvider: "1Password",
          expectedHistoricalCredentialCount: 4,
          observedHistoricalCredentialCount: 4,
          candidateRecordId: resetStage === "REGISTER_NEW" ? null : candidateRecordId,
          candidateLabel: resetStage === "REGISTER_NEW" ? null : "1Password · 5",
          activeTotpCount: 1,
          activeRecoveryCodeCount: 10,
          nextAction
        });
      }
      if (path === "/api/web/auth/webauthn/registration/options") {
        return json(route, {
          requestId: "00000000-0000-4000-8000-000000000301",
          challenge: "BAUG",
          timeoutMillis: 30_000,
          relyingPartyId: "atenea.yudri.es",
          relyingPartyName: "Atenea",
          userHandle: "BAUG",
          opaqueUserName: "operator-synthetic",
          credentialParameters: [{ type: "public-key", algorithm: -7 }],
          credentials: [],
          userVerification: "required",
          residentKey: "required",
          attestation: "none"
        });
      }
      if (path === "/api/web/auth/webauthn/registration") {
        registrationProvider = request.postDataJSON().providerCategory;
        resetStage = "PROVE_NEW";
        return route.fulfill({ status: 204, body: "" });
      }
      if (path === "/api/web/auth/webauthn/ownership/options") {
        proofTarget = request.postDataJSON().recordId;
        return json(route, {
          requestId: "00000000-0000-4000-8000-000000000302",
          challenge: "BAUG",
          timeoutMillis: 30_000,
          relyingPartyId: "atenea.yudri.es",
          credentialParameters: [],
          credentials: [{ type: "public-key", id: "AQID", transports: ["internal"] }],
          userVerification: "required"
        });
      }
      if (path === "/api/web/auth/webauthn/ownership") {
        proofProvider = request.postDataJSON().providerCategory;
        resetStage = "COMMIT_READY";
        return json(route, {
          recordId: candidateRecordId,
          label: "1Password · 5",
          providerCategory: "ONE_PASSWORD",
          verifiedAt: "2026-08-19T12:00:00Z"
        });
      }
      if (path === `/api/auth/webauthn/passkey-reset/${candidateRecordId}/commit`) {
        committedCandidate = candidateRecordId;
        resetStage = "COMPLETE";
        return json(route, {
          state: "COMMITTED",
          activePasskeyCount: 1,
          revokedHistoricalCount: 4,
          activeTotpCount: 1,
          activeRecoveryCodeCount: 10,
          credentialVersion: 7
        });
      }
      if (path === "/actuator/health") return json(route, { status: "UP" });
      if (path === "/api/mobile/operations/hosts") return json(route, []);
      if (path === "/api/mobile/operations/incidents") return json(route, { incidents: [] });
      return json(route, {});
    });

    await page.goto("/#/settings", { waitUntil: "networkidle" });
    const resetPanel = page.getByLabel("Reinicio controlado de passkeys");
    await expect(resetPanel).toContainText("1 de 3 · Registrar en 1Password");
    await expect(page.getByRole("button", { name: "Registrar passkey en 1Password" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Revocar en Atenea" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Preparar TOTP" })).toHaveCount(0);
    await expectInFirstViewport(page, resetPanel);
    await retainScreenshot(page, `${viewport.name}-passkey-controlled-reset-register.png`);

    await page.getByRole("button", { name: "Registrar passkey en 1Password" }).click();
    await expect(resetPanel).toContainText("2 de 3 · Verificar la passkey nueva");
    expect(registrationProvider).toBe("ONE_PASSWORD");
    await page.getByRole("button", { name: "Verificar passkey nueva" }).click();
    await expect(resetPanel).toContainText("3 de 3 · Sustituir las históricas");
    expect(proofTarget).toBe(candidateRecordId);
    expect(proofProvider).toBe("ONE_PASSWORD");
    await page.getByRole("button", { name: "Sustituir passkeys históricas" }).click();
    await expect(resetPanel).toContainText("Reinicio completado");
    await expect(resetPanel).toContainText("una passkey activa en 1Password");
    expect(committedCandidate).toBe(candidateRecordId);
    await expectNoHorizontalOverflow(page);
    await retainScreenshot(page, `${viewport.name}-passkey-controlled-reset.png`);
  });
}

async function expectNoHorizontalOverflow(page: Page) {
  const widths = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    document: document.documentElement.scrollWidth
  }));
  expect(widths.document).toBeLessThanOrEqual(widths.viewport);
}

async function expectInFirstViewport(page: Page, locator: Locator) {
  const box = await locator.boundingBox();
  const viewport = page.viewportSize();
  expect(box).not.toBeNull();
  expect(viewport).not.toBeNull();
  expect(box!.y).toBeGreaterThanOrEqual(0);
  expect(box!.y + box!.height).toBeLessThanOrEqual(viewport!.height);
}

async function retainScreenshot(page: Page, name: string) {
  const directory = process.env.ATENEA_VISUAL_EVIDENCE_DIR;
  if (directory) await page.screenshot({ path: `${directory}/${name}`, fullPage: false });
}
