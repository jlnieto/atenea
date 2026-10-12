import { expect, Page, Route, test } from "@playwright/test";

const sessionId = 21;
const runId = 103;
const changeKey = "59315b6e-59bc-4884-9def-356e1ca86ef4";
type State = {
  surfaceEnabled: boolean; state: string; title: string; blocker: string | null;
  primaryAction: string; primaryActionLabel: string | null; primaryActionAvailable: boolean;
  requiredRole: string | null; targetWorkSessionId: number | null; targetAgentRunId: number | null;
};
function closing(): State {
  return { surfaceEnabled: true, state: "CLOSING_REMOTE", title: "Cerrando el cambio",
    blocker: "Confirmación pendiente.", primaryAction: "RECONCILE_REMOTE_CLOSE",
    primaryActionLabel: "Reconciliar cierre", primaryActionAvailable: true,
    requiredRole: "ROUTINE_OPERATOR", targetWorkSessionId: sessionId, targetAgentRunId: null };
}
async function setup(page: Page, state: State, role = "PLATFORM_ADMINISTRATOR", closeStatus = 200) {
  const effects: { path: string; body: Record<string, unknown> }[] = [];
  const json = (route: Route, value: unknown, status = 200) => route.fulfill({
    status, contentType: "application/json", body: JSON.stringify(value) });
  await page.route("**/api/**", async route => {
    const req = route.request(), path = new URL(req.url()).pathname;
    if (path === "/api/web/auth/refresh") return json(route, {
      accessToken: "synthetic", accessTokenExpiresAt: "2099-01-01T00:00:00Z",
      operator: { id: 1, email: "operator@example.invalid", displayName: "Synthetic", codexOperationsRole: role }
    });
    if (req.method() === "POST") {
      effects.push({ path, body: req.postDataJSON() || {} });
      if (path === `/api/sessions/${sessionId}/close`) return json(route, {}, closeStatus);
      if (path === `/api/runs/${runId}/recovery`) return json(route, { state: "ACCEPTED" });
      return json(route, { code: "UNEXPECTED_EFFECT" }, 409);
    }
    if (path === `/api/mobile/sessions/${sessionId}/summary`) return json(route, {
      conversation: { view: {
        session: { id: sessionId, projectId: 1, developmentChangeKey: changeKey, title: "Synthetic change",
          status: "OPEN", operationalState: "READY", closeRetryable: false },
        runInProgress: false, canCreateTurn: false,
        latestRun: { id: runId, status: "FAILED", errorSummary: "Synthetic blocker" },
        lastError: null, lastAgentResponse: null
      }, recentTurns: [], recentTurnLimit: 50, historyTruncated: false },
      approvedDeliverables: { deliverables: [] }, approvedPriceEstimate: null,
      actions: {}, insights: {}, operatorState: state
    });
    if (path.endsWith("/attachments/capability")) return json(route, {
      state: "BLOCKED", blockedReason: "SESSION_NOT_ELIGIBLE", acceptedContentTypes: [],
      currentSessionBytes: 0, maxSessionBytes: 0, remainingSessionBytes: 0,
      maxFileBytes: 0, maxAttachmentsPerTurn: 0, maxAttachmentBytesPerTurn: 0
    });
    if (path === "/api/codex/catalog") return json(route, { message: "Not configured" }, 404);
    if (path.endsWith("/codex-detail")) return json(route, {
      runId, status: "FAILED", currentState: "FAILED", requiredNextAction: "RETRY", elapsedMillis: 1200
    });
    if (path.endsWith("/progress")) return json(route, {
      runId, currentState: "FAILED", requiredNextAction: "RETRY", elapsedMillis: 1200, events: []
    });
    if (path.endsWith("/hosts")) return json(route, []);
    if (path.endsWith("/incidents")) return json(route, { incidents: [] });
    return json(route, {});
  });
  await page.goto(`/#/conversation/1/${sessionId}`, { waitUntil: "networkidle" });
  await expect(page.getByLabel("Estado operativo del cierre remoto")).toBeVisible();
  return effects;
}

for (const width of [390, 1440]) {
  test(`same-change reconciliation at width ${width} has no legacy confirmation or extra effects`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const effects = await setup(page, closing());
    expect(effects).toEqual([]);
    const panel = page.getByLabel("Estado operativo del cierre remoto");
    await expect(panel.getByRole("button", { name: "Reconciliar cierre", exact: true })).toBeEnabled();
    await panel.getByRole("button", { name: "Reconciliar cierre", exact: true }).click();
    await expect(panel.getByText("Reconciliación solicitada. Se mantendrá la misma operación de cierre.")).toBeVisible();
    expect(effects.map(e => e.path)).toEqual([`/api/sessions/${sessionId}/close`]);
    await expect(page.getByRole("group", { name: "Confirmar reconciliación del cierre" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Crear sesión", exact: true })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Cerrar", exact: true })).toHaveCount(0);
    const widths = await page.evaluate(() => [document.documentElement.clientWidth, document.documentElement.scrollWidth]);
    expect(widths[1]).toBeLessThanOrEqual(widths[0]);
  });
}

test("released capacity retries original run explicitly without a new turn or session", async ({ page }) => {
  const effects = await setup(page, { ...closing(), state: "CAPACITY_RELEASED", title: "Capacidad liberada",
    primaryAction: "RETRY_AGENT_RUN", primaryActionLabel: "Reintentar tarea",
    targetWorkSessionId: 19, targetAgentRunId: runId }, "ROUTINE_OPERATOR");
  expect(effects).toEqual([]);
  await page.getByLabel("Estado operativo del cierre remoto").getByRole("button", { name: "Reintentar tarea" }).click();
  await expect(page.getByText("Reintento solicitado. La tarea original y sus adjuntos permanecen conservados.")).toBeVisible();
  expect(effects.map(e => e.path)).toEqual([`/api/runs/${runId}/recovery`]);
  expect(effects[0].body.workSessionId).toBe(sessionId);
  expect(effects[0].body.action).toBe("RETRY");
});

test("missing role cannot reconcile", async ({ page }) => {
  const effects = await setup(page, { ...closing(), requiredRole: "PLATFORM_ADMINISTRATOR" }, "ROUTINE_OPERATOR");
  await expect(page.getByRole("button", { name: "Reconciliar cierre" })).toBeDisabled();
  await expect(page.getByText("Requiere administración de plataforma.")).toBeVisible();
  expect(effects).toEqual([]);
});

for (const state of ["LEGACY_CLOSE_REQUIRED", "CLOSED_OWNER_BLOCKS_CAPACITY", "REMOTE_CLOSE_BLOCKED"]) {
  test(`old adoption state ${state} cannot call removed administrative APIs`, async ({ page }) => {
    const effects = await setup(page, { ...closing(), state, targetWorkSessionId: 19 });
    await expect(page.getByRole("button", { name: "Reconciliar cierre" })).toBeDisabled();
    await expect(page.getByRole("group", { name: "Confirmar reconciliación del cierre" })).toHaveCount(0);
    expect(effects).toEqual([]);
  });
}

test("foreign closing target cannot act from this conversation", async ({ page }) => {
  const effects = await setup(page, { ...closing(), targetWorkSessionId: 19 });
  await expect(page.getByRole("button", { name: "Reconciliar cierre" })).toBeDisabled();
  expect(effects).toEqual([]);
});

test("conflict requires refresh instead of opening an adoption plan", async ({ page }) => {
  const effects = await setup(page, closing(), "ROUTINE_OPERATOR", 409);
  await page.getByRole("button", { name: "Reconciliar cierre" }).click();
  await expect(page.getByText("El estado cambió. Actualiza antes de continuar.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Reconciliar cierre" })).toBeDisabled();
  expect(effects.map(e => e.path)).toEqual([`/api/sessions/${sessionId}/close`]);
});
