import { expect, Page, Route, test } from "@playwright/test";

type OperatorState = {
  surfaceEnabled: boolean;
  state: string;
  title: string;
  blocker: string | null;
  primaryAction: string;
  primaryActionLabel: string | null;
  primaryActionAvailable: boolean;
  requiredRole: string | null;
  targetWorkSessionId: number | null;
  targetAgentRunId: number | null;
};

type SyntheticState = {
  operatorRole: "ROUTINE_OPERATOR" | "PLATFORM_ADMINISTRATOR";
  operatorState: OperatorState;
  released: boolean;
  closeRequests: number;
  planRequests: Record<string, unknown>[];
  confirmationRequests: Record<string, unknown>[];
  recoveryRequests: Record<string, unknown>[];
  freshRequests?: Record<string, unknown>[];
  freshFailuresRemaining?: number;
  staleConfirmation?: boolean;
  blockedConfirmation?: boolean;
  planTargetWorkSessionId?: number;
  closedSession?: boolean;
};

const currentSessionId = 17;
const closedOwnerId = 16;
const failedRunId = 96;

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: "application/json",
    headers: { "Cache-Control": "no-store" },
    body: JSON.stringify(body)
  });
}

function conversationEnvelope(closed = false) {
  return {
    view: {
      session: {
        id: currentSessionId,
        projectId: 1,
        title: "Validación sintética del cierre remoto",
        status: closed ? "CLOSED" : "OPEN",
        operationalState: "READY",
        closeRetryable: false
      },
      runInProgress: false,
      canCreateTurn: false,
      latestRun: {
        id: failedRunId,
        status: "FAILED",
        errorSummary: "La capacidad necesaria sigue ocupada."
      },
      lastError: null,
      lastAgentResponse: null
    },
    recentTurns: [],
    recentTurnLimit: 50,
    historyTruncated: false
  };
}

function freshConversationEnvelope() {
  return {
    view: {
      session: {
        id: 18,
        projectId: 1,
        title: "Validación sintética del cierre remoto",
        status: "OPEN",
        operationalState: "READY",
        closeRetryable: false
      },
      runInProgress: false,
      canCreateTurn: true,
      latestRun: null,
      lastError: null,
      lastAgentResponse: null
    },
    recentTurns: [],
    recentTurnLimit: 50,
    historyTruncated: false
  };
}

function releasedState(): OperatorState {
  return {
    surfaceEnabled: true,
    state: "CAPACITY_RELEASED",
    title: "Capacidad liberada",
    blocker: null,
    primaryAction: "RETRY_AGENT_RUN",
    primaryActionLabel: "Reintentar tarea",
    primaryActionAvailable: true,
    requiredRole: "ROUTINE_OPERATOR",
    targetWorkSessionId: closedOwnerId,
    targetAgentRunId: failedRunId
  };
}

function summary(apiState: SyntheticState) {
  return {
    conversation: conversationEnvelope(apiState.closedSession),
    approvedDeliverables: { deliverables: [] },
    approvedPriceEstimate: null,
    actions: {},
    insights: {},
    operatorState: apiState.released ? releasedState() : apiState.operatorState
  };
}

for (const viewport of [
  { name: "desktop", width: 1440, height: 900 },
  { name: "mobile", width: 390, height: 844 }
] as const) {
  test(`${viewport.name} released source keeps its incomplete fresh operation actionable`, async ({ page }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    const apiState: SyntheticState = {
      operatorRole: "PLATFORM_ADMINISTRATOR",
      operatorState: {
        surfaceEnabled: true,
        state: "SOURCE_ADVANCED",
        title: "Nueva sesión pendiente",
        blocker: "La sesión anterior ya está cerrada. Completa la creación de su única sucesora vacía.",
        primaryAction: "START_FRESH_SESSION",
        primaryActionLabel: "Completar sesión nueva",
        primaryActionAvailable: true,
        requiredRole: "PLATFORM_ADMINISTRATOR",
        targetWorkSessionId: currentSessionId,
        targetAgentRunId: failedRunId
      },
      released: false,
      closeRequests: 0,
      planRequests: [],
      confirmationRequests: [],
      recoveryRequests: [],
      freshRequests: [],
      closedSession: true
    };
    await openConversation(page, apiState, `${viewport.name}-fresh-recovery`);

    await expect(page.getByText("Nueva sesión pendiente", { exact: true })).toBeVisible();
    await expect(page.getByText(
      "La sesión anterior ya está cerrada. Completa la creación de su única sucesora vacía.",
      { exact: true }
    )).toBeVisible();
    await expectPrimaryActionInFirstViewport(page, "Completar sesión nueva");
    await expectNoHorizontalOverflow(page);
    await retainScreenshot(page, `${viewport.name}-fresh-recovery.png`);

    await page.getByRole("button", { name: "Completar sesión nueva" }).click();
    await expect(page).toHaveURL(/#\/conversation\/1\/18$/);
    expect(apiState.freshRequests).toHaveLength(1);
    expect(apiState.recoveryRequests).toHaveLength(0);
  });
}

for (const viewport of [
  { name: "desktop", width: 1440, height: 900 },
  { name: "mobile", width: 390, height: 844 }
] as const) {
  test(`${viewport.name} projects exposes closed-source recovery without creating or rescuing`, async ({ page }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    const observedPosts: string[] = [];
    await installProjectsRecoveryApi(page, observedPosts);
    await page.goto(`/?case=${viewport.name}-project-recovery#/projects`, { waitUntil: "networkidle" });
    const projectCard = page.locator(".project-card").filter({
      has: page.getByRole("heading", { name: "Atenea", exact: true })
    });

    await expect(projectCard.getByText("Nueva sesión pendiente", { exact: true })).toBeVisible();
    await expect(projectCard.getByText(
      "La sesión anterior ya está cerrada. Continúa la creación de su única sucesora vacía.",
      { exact: true }
    )).toBeVisible();
    await expect(projectCard.getByRole("button", { name: "Crear sesión" })).toHaveCount(0);
    await expect(projectCard.getByRole("button", { name: "Rescate" })).toHaveCount(0);
    await expect(projectCard.getByText("Título de nueva sesión", { exact: true })).toHaveCount(0);
    await expectPrimaryActionInFirstViewport(page, "Continuar recuperación");
    await expectNoHorizontalOverflow(page);
    await retainScreenshot(page, `${viewport.name}-project-recovery.png`);

    await projectCard.getByRole("button", { name: "Continuar recuperación" }).click();
    await expect(page).toHaveURL(/#\/session\/1\/17$/);
    expect(observedPosts).toHaveLength(0);
  });
}

async function installProjectsRecoveryApi(page: Page, observedPosts: string[]) {
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/web/auth/refresh") {
      return json(route, authSession("PLATFORM_ADMINISTRATOR"));
    }
    if (request.method() === "POST") {
      observedPosts.push(path);
      return json(route, { code: "UNEXPECTED_MUTATION" }, 409);
    }
    if (path === "/api/mobile/projects/overview") {
      return json(route, [{
        projectId: 1,
        projectName: "Atenea",
        description: "Self-hosted Atenea source repository",
        defaultBaseBranch: "main",
        session: {
          sessionId: currentSessionId,
          status: "CLOSED",
          title: "Validación sintética del cierre remoto",
          runInProgress: false,
          closeBlockedState: null,
          pullRequestStatus: null,
          lastActivityAt: "2026-08-09T10:00:00Z",
          recoveryPending: true
        }
      }]);
    }
    if (path === `/api/mobile/sessions/${currentSessionId}/summary`) {
      return json(route, summary({
        operatorRole: "PLATFORM_ADMINISTRATOR",
        operatorState: {
          surfaceEnabled: true,
          state: "SOURCE_ADVANCED",
          title: "Nueva sesión pendiente",
          blocker: "La sesión anterior ya está cerrada. Completa la creación de su única sucesora vacía.",
          primaryAction: "START_FRESH_SESSION",
          primaryActionLabel: "Completar sesión nueva",
          primaryActionAvailable: true,
          requiredRole: "PLATFORM_ADMINISTRATOR",
          targetWorkSessionId: currentSessionId,
          targetAgentRunId: failedRunId
        },
        released: false,
        closeRequests: 0,
        planRequests: [],
        confirmationRequests: [],
        recoveryRequests: [],
        closedSession: true
      }));
    }
    if (path === "/api/mobile/operations/hosts") return json(route, []);
    if (path === "/api/mobile/operations/incidents") return json(route, { incidents: [] });
    return json(route, {});
  });
}

async function installSyntheticApi(page: Page, apiState: SyntheticState) {
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/web/auth/refresh") {
      return json(route, authSession(apiState.operatorRole));
    }
    if (path === `/api/mobile/sessions/${currentSessionId}/summary`) {
      return json(route, summary(apiState));
    }
    if (path === "/api/mobile/sessions/18/summary") {
      return json(route, {
        conversation: freshConversationEnvelope(),
        approvedDeliverables: { deliverables: [] },
        approvedPriceEstimate: null,
        actions: {},
        insights: {},
        operatorState: {
          surfaceEnabled: false,
          state: "DEFAULT",
          title: "Lista",
          blocker: null,
          primaryAction: "NONE",
          primaryActionLabel: null,
          primaryActionAvailable: false,
          requiredRole: null,
          targetWorkSessionId: null,
          targetAgentRunId: null
        }
      });
    }
    if (path === `/api/mobile/sessions/${currentSessionId}/attachments/capability`) {
      return json(route, {
        state: "BLOCKED",
        blockedReason: "SESSION_NOT_ELIGIBLE",
        message: "Adjuntos no disponibles en esta prueba.",
        nextAction: "Continúa sin adjuntos.",
        policyRevision: "synthetic",
        workerCompatibility: "NOT_CHECKED",
        acceptedContentTypes: [],
        currentSessionBytes: 0,
        maxSessionBytes: 0,
        remainingSessionBytes: 0,
        maxFileBytes: 0,
        maxAttachmentsPerTurn: 0,
        maxAttachmentBytesPerTurn: 0
      });
    }
    if (path === "/api/codex/catalog") {
      return json(route, { message: "Not configured" }, 404);
    }
    if (path === `/api/runs/${failedRunId}/codex-detail`) {
      return json(route, {
        runId: failedRunId,
        status: "FAILED",
        currentState: "FAILED",
        requiredNextAction: "RETRY",
        elapsedMillis: 1200,
        modelId: "synthetic",
        reasoningEffort: "high",
        codexVersion: "synthetic"
      });
    }
    if (path === `/api/runs/${failedRunId}/progress`) {
      return json(route, {
        runId: failedRunId,
        currentState: "FAILED",
        requiredNextAction: "RETRY",
        elapsedMillis: 1200,
        events: []
      });
    }
    if (path === `/api/sessions/${currentSessionId}/close` && request.method() === "POST") {
      apiState.closeRequests += 1;
      return json(route, { id: currentSessionId, status: "CLOSING" });
    }
    if (path === `/api/admin/work-sessions/${closedOwnerId}/remote-close-plans`
        && request.method() === "POST") {
      apiState.planRequests.push(request.postDataJSON());
      return json(route, {
        planId: "10000000-0000-4000-8000-000000000001",
        workSessionId: apiState.planTargetWorkSessionId ?? closedOwnerId,
        operation: "RECONCILE_REMOTE_CLOSE",
        state: "READY_FOR_CONFIRMATION",
        requiredRole: "PLATFORM_ADMINISTRATOR",
        ownershipFingerprintSha256: "a".repeat(64),
        expiresAt: "2099-01-01T00:10:00Z",
        consumed: false,
        expectedImpact: "Synthetic safe impact",
        valuesExposed: false,
        createdAt: "2099-01-01T00:00:00Z"
      });
    }
    if (path === `/api/admin/work-sessions/${closedOwnerId}/remote-close-reconciliations`
        && request.method() === "POST") {
      apiState.confirmationRequests.push(request.postDataJSON());
      if (apiState.staleConfirmation) {
        return json(route, { code: "REMOTE_CLOSE_PLAN_STALE" }, 409);
      }
      if (apiState.blockedConfirmation) {
        return json(route, {
          operationId: "20000000-0000-4000-8000-000000000001",
          planId: "10000000-0000-4000-8000-000000000001",
          workSessionId: closedOwnerId,
          operation: "RECONCILE_REMOTE_CLOSE",
          state: "BLOCKED",
          revision: 2,
          ownershipFingerprintSha256: "a".repeat(64),
          errorCode: "WORKSPACE_RELEASE_PREFLIGHT_REJECTED",
          errorCategory: "OWNERSHIP",
          nextAction: "CONTACT_PLATFORM_ADMINISTRATOR",
          retryable: false,
          receiptSha256: null,
          requestedAt: "2099-01-01T00:01:00Z",
          updatedAt: "2099-01-01T00:01:01Z",
          releasedAt: null,
          valuesExposed: false
        });
      }
      apiState.released = true;
      return json(route, {
        operationId: "20000000-0000-4000-8000-000000000001",
        planId: "10000000-0000-4000-8000-000000000001",
        workSessionId: closedOwnerId,
        operation: "RECONCILE_REMOTE_CLOSE",
        state: "RELEASED",
        revision: 2,
        ownershipFingerprintSha256: "a".repeat(64),
        errorCode: null,
        errorCategory: null,
        nextAction: "NONE",
        retryable: false,
        receiptSha256: "b".repeat(64),
        requestedAt: "2099-01-01T00:01:00Z",
        updatedAt: "2099-01-01T00:01:01Z",
        releasedAt: "2099-01-01T00:01:01Z",
        valuesExposed: false
      });
    }
    if (path === `/api/runs/${failedRunId}/recovery` && request.method() === "POST") {
      apiState.recoveryRequests.push(request.postDataJSON());
      return json(route, {
        state: "ACCEPTED",
        summary: "Reintento aceptado.",
        requiredNextAction: "NONE"
      });
    }
    if (path === `/api/mobile/sessions/${currentSessionId}/start-fresh`
        && request.method() === "POST") {
      apiState.freshRequests ??= [];
      apiState.freshRequests.push(request.postDataJSON());
      if ((apiState.freshFailuresRemaining ?? 0) > 0) {
        apiState.freshFailuresRemaining = (apiState.freshFailuresRemaining ?? 0) - 1;
        return json(route, { code: "REMOTE_RESPONSE_UNCONFIRMED" }, 503);
      }
      return json(route, {
        operationId: "30000000-0000-4000-8000-000000000001",
        state: "COMPLETED",
        sourceWorkSessionId: currentSessionId,
        resultWorkSessionId: 18,
        created: true,
        view: freshConversationEnvelope()
      });
    }
    if (path === "/api/mobile/operations/hosts") return json(route, []);
    if (path === "/api/mobile/operations/incidents") return json(route, { incidents: [] });
    return json(route, {});
  });
}

function authSession(role: SyntheticState["operatorRole"]) {
  return {
    accessToken: "synthetic-access",
    accessTokenExpiresAt: "2099-01-01T00:00:00Z",
    operator: {
      id: 1,
      email: "operator@example.invalid",
      displayName: "Operador sintético",
      codexOperationsRole: role
    }
  };
}

async function openConversation(page: Page, apiState: SyntheticState, caseName: string) {
  await installSyntheticApi(page, apiState);
  await page.goto(`/?case=${caseName}#/conversation/1/${currentSessionId}`, { waitUntil: "networkidle" });
  await expect(page.getByLabel("Estado operativo del cierre remoto")).toBeVisible();
}

async function retainScreenshot(page: Page, name: string) {
  const evidenceDirectory = process.env.ATENEA_VISUAL_EVIDENCE_DIR;
  if (evidenceDirectory) {
    await page.screenshot({ path: `${evidenceDirectory}/${name}`, fullPage: false });
  }
}

async function expectNoHorizontalOverflow(page: Page) {
  const widths = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    document: document.documentElement.scrollWidth,
    panelClient: document.querySelector<HTMLElement>(".remote-close-state")?.clientWidth || 0,
    panelScroll: document.querySelector<HTMLElement>(".remote-close-state")?.scrollWidth || 0
  }));
  expect(widths.document).toBeLessThanOrEqual(widths.viewport);
  expect(widths.panelScroll).toBeLessThanOrEqual(widths.panelClient);
}

async function expectPrimaryActionInFirstViewport(page: Page, name: string) {
  const button = page.getByRole("button", { name });
  await expect(button).toBeVisible();
  const box = await button.boundingBox();
  expect(box).not.toBeNull();
  expect(box!.x).toBeGreaterThanOrEqual(0);
  expect(box!.x + box!.width).toBeLessThanOrEqual(page.viewportSize()!.width);
  expect(box!.y).toBeGreaterThanOrEqual(0);
  expect(box!.y + box!.height).toBeLessThanOrEqual(page.viewportSize()!.height);
}

test("closing state leads with same-operation reconciliation", async ({ page }) => {
  const apiState: SyntheticState = {
    operatorRole: "ROUTINE_OPERATOR",
    operatorState: {
      surfaceEnabled: true,
      state: "CLOSING_REMOTE",
      title: "Cerrando · liberando recursos remotos",
      blocker: "La respuesta del cierre está pendiente de confirmar.",
      primaryAction: "RECONCILE_REMOTE_CLOSE",
      primaryActionLabel: "Reconciliar cierre",
      primaryActionAvailable: true,
      requiredRole: "ROUTINE_OPERATOR",
      targetWorkSessionId: currentSessionId,
      targetAgentRunId: failedRunId
    },
    released: false,
    closeRequests: 0,
    planRequests: [],
    confirmationRequests: [],
    recoveryRequests: []
  };
  await openConversation(page, apiState, "closing");

  await expect(page.getByText("Cerrando · liberando recursos remotos", { exact: true })).toBeVisible();
  await expect(page.getByText("La respuesta del cierre está pendiente de confirmar.", { exact: true })).toBeVisible();
  await retainScreenshot(page, "desktop-closing.png");
  await page.getByRole("button", { name: "Reconciliar cierre" }).click();

  await expect.poll(() => apiState.closeRequests).toBe(1);
  expect(apiState.planRequests).toHaveLength(0);
  expect(apiState.recoveryRequests).toHaveLength(0);
});

test("legacy owner requires confirmation before release and retry", async ({ page }) => {
  const apiState: SyntheticState = {
    operatorRole: "PLATFORM_ADMINISTRATOR",
    operatorState: {
      surfaceEnabled: true,
      state: "CLOSED_OWNER_BLOCKS_CAPACITY",
      title: "Bloqueada por una sesión cerrada",
      blocker: "Otra sesión cerrada conserva la capacidad necesaria. El reintento estará disponible después de reconciliar su cierre.",
      primaryAction: "RECONCILE_REMOTE_CLOSE",
      primaryActionLabel: "Reconciliar cierre",
      primaryActionAvailable: true,
      requiredRole: "PLATFORM_ADMINISTRATOR",
      targetWorkSessionId: closedOwnerId,
      targetAgentRunId: failedRunId
    },
    released: false,
    closeRequests: 0,
    planRequests: [],
    confirmationRequests: [],
    recoveryRequests: []
  };
  await openConversation(page, apiState, "legacy-confirmation");

  await expect(page.getByRole("button", { name: "Reintentar", exact: true })).toHaveCount(0);
  await expect(page.getByText("Reconciliar el cierre antes de reintentar", { exact: true })).toBeVisible();
  await expect(page.getByText("Reintentar de forma segura", { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "Reconciliar cierre" }).click();
  await expect(page.getByRole("group", { name: "Confirmar reconciliación del cierre" })).toBeVisible();
  await expect(page.getByText(`Objetivo: WorkSession ${closedOwnerId}`, { exact: true })).toBeVisible();
  await expect(page.getByText(`Sesión abierta: WorkSession ${currentSessionId}. Solo se liberará el ownership remoto activo del objetivo; historial, Git, runs y adjuntos se conservan.`, { exact: true })).toBeVisible();
  expect(apiState.planRequests).toHaveLength(1);
  expect(apiState.confirmationRequests).toHaveLength(0);
  expect(apiState.recoveryRequests).toHaveLength(0);

  await page.getByRole("button", { name: `Confirmar WorkSession ${closedOwnerId}` }).click();
  await expect(page.getByText("Capacidad liberada", { exact: true })).toBeVisible();
  await expect(page.getByText("El reintento es una decisión explícita: no se ha vuelto a enviar ninguna instrucción.", { exact: true })).toBeVisible();
  await expect(page.getByText("Usa «Reintentar tarea» en el estado operativo", { exact: true })).toBeVisible();
  expect(apiState.confirmationRequests).toHaveLength(1);
  expect(apiState.confirmationRequests[0]).toMatchObject({
    operation: "RECONCILE_REMOTE_CLOSE",
    planId: "10000000-0000-4000-8000-000000000001",
    ownershipFingerprintSha256: "a".repeat(64)
  });
  expect(apiState.recoveryRequests).toHaveLength(0);
  await retainScreenshot(page, "desktop-capacity-released.png");

  await page.getByRole("button", { name: "Reintentar tarea" }).click();
  await expect.poll(() => apiState.recoveryRequests.length).toBe(1);
  expect(apiState.recoveryRequests[0]).toMatchObject({
    workSessionId: currentSessionId,
    action: "RETRY"
  });
});

test("routine operator sees the required role and cannot create a legacy plan", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const apiState: SyntheticState = {
    operatorRole: "ROUTINE_OPERATOR",
    operatorState: {
      surfaceEnabled: true,
      state: "CLOSED_OWNER_BLOCKS_CAPACITY",
      title: "Bloqueada por una sesión cerrada",
      blocker: "Otra sesión cerrada conserva la capacidad necesaria.",
      primaryAction: "RECONCILE_REMOTE_CLOSE",
      primaryActionLabel: "Reconciliar cierre",
      primaryActionAvailable: true,
      requiredRole: "PLATFORM_ADMINISTRATOR",
      targetWorkSessionId: closedOwnerId,
      targetAgentRunId: failedRunId
    },
    released: false,
    closeRequests: 0,
    planRequests: [],
    confirmationRequests: [],
    recoveryRequests: []
  };
  await openConversation(page, apiState, "routine-role");

  await expect(page.getByText("Requiere administración de plataforma.", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Reconciliar cierre" })).toBeDisabled();
  await retainScreenshot(page, "mobile-required-role.png");
  expect(apiState.planRequests).toHaveLength(0);
});

test("mismatched plan target is rejected until an explicit refresh", async ({ page }) => {
  const apiState: SyntheticState = {
    operatorRole: "PLATFORM_ADMINISTRATOR",
    operatorState: {
      surfaceEnabled: true,
      state: "CLOSED_OWNER_BLOCKS_CAPACITY",
      title: "Bloqueada por una sesión cerrada",
      blocker: "Otra sesión cerrada conserva la capacidad necesaria.",
      primaryAction: "RECONCILE_REMOTE_CLOSE",
      primaryActionLabel: "Reconciliar cierre",
      primaryActionAvailable: true,
      requiredRole: "PLATFORM_ADMINISTRATOR",
      targetWorkSessionId: closedOwnerId,
      targetAgentRunId: failedRunId
    },
    released: false,
    closeRequests: 0,
    planRequests: [],
    confirmationRequests: [],
    recoveryRequests: [],
    planTargetWorkSessionId: closedOwnerId - 1
  };
  await openConversation(page, apiState, "mismatched-plan-target");

  await page.getByRole("button", { name: "Reconciliar cierre" }).click();

  await expect(page.getByRole("group", { name: "Confirmar reconciliación del cierre" })).toHaveCount(0);
  await expect(page.getByRole("alert")).toContainText("El estado se conserva");
  await expect(page.getByRole("button", { name: "Reconciliar cierre" })).toBeDisabled();
  expect(apiState.planRequests).toHaveLength(1);
  expect(apiState.confirmationRequests).toHaveLength(0);
});

for (const viewport of [
  { name: "desktop", width: 1440, height: 900 },
  { name: "mobile", width: 390, height: 844 }
] as const) {
test(`${viewport.name} exact preflight block exposes one fresh safe confirmation`, async ({ page }) => {
  await page.setViewportSize({ width: viewport.width, height: viewport.height });
  const apiState: SyntheticState = {
    operatorRole: "PLATFORM_ADMINISTRATOR",
    operatorState: {
      surfaceEnabled: true,
      state: "REMOTE_CLOSE_BLOCKED",
      title: "Cierre remoto bloqueado",
      blocker: "No se liberó ningún recurso. Genera una nueva confirmación administrativa.",
      primaryAction: "RECONCILE_REMOTE_CLOSE",
      primaryActionLabel: "Volver a validar cierre",
      primaryActionAvailable: true,
      requiredRole: "PLATFORM_ADMINISTRATOR",
      targetWorkSessionId: closedOwnerId,
      targetAgentRunId: null
    },
    released: false,
    closeRequests: 0,
    planRequests: [],
    confirmationRequests: [],
    recoveryRequests: []
  };
  await openConversation(page, apiState, `${viewport.name}-preflight-recovery`);

  await expect(page.getByText("Cierre remoto bloqueado", { exact: true })).toBeVisible();
  await expect(page.getByText(
    "No se liberó ningún recurso. Genera una nueva confirmación administrativa.",
    { exact: true }
  )).toBeVisible();
  await expectPrimaryActionInFirstViewport(page, "Volver a validar cierre");
  await expectNoHorizontalOverflow(page);
  await retainScreenshot(page, `${viewport.name}-preflight-recovery.png`);

  await page.getByRole("button", { name: "Volver a validar cierre" }).click();
  await expect(page.getByRole("group", { name: "Confirmar reconciliación del cierre" })).toBeVisible();
  await expect(page.getByText(`Objetivo: WorkSession ${closedOwnerId}`, { exact: true })).toBeVisible();
  await expect(page.getByText(`Sesión abierta: WorkSession ${currentSessionId}. Solo se liberará el ownership remoto activo del objetivo; historial, Git, runs y adjuntos se conservan.`, { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: `Confirmar WorkSession ${closedOwnerId}` })).toBeEnabled();
  await expectNoHorizontalOverflow(page);
  await retainScreenshot(page, `${viewport.name}-preflight-recovery-confirmation.png`);
  expect(apiState.planRequests).toHaveLength(1);
  expect(apiState.confirmationRequests).toHaveLength(0);
  expect(apiState.recoveryRequests).toHaveLength(0);
});

test(`${viewport.name} stale confirmation is discarded until an explicit screen refresh`, async ({ page }) => {
  await page.setViewportSize({ width: viewport.width, height: viewport.height });
  const apiState: SyntheticState = {
    operatorRole: "PLATFORM_ADMINISTRATOR",
    operatorState: {
      surfaceEnabled: true,
      state: "CLOSED_OWNER_BLOCKS_CAPACITY",
      title: "Bloqueada por una sesión cerrada",
      blocker: "Otra sesión cerrada conserva la capacidad necesaria.",
      primaryAction: "RECONCILE_REMOTE_CLOSE",
      primaryActionLabel: "Reconciliar cierre",
      primaryActionAvailable: true,
      requiredRole: "PLATFORM_ADMINISTRATOR",
      targetWorkSessionId: closedOwnerId,
      targetAgentRunId: failedRunId
    },
    released: false,
    closeRequests: 0,
    planRequests: [],
    confirmationRequests: [],
    recoveryRequests: [],
    staleConfirmation: true
  };
  await openConversation(page, apiState, "stale-confirmation");

  await page.getByRole("button", { name: "Reconciliar cierre" }).click();
  await page.getByRole("button", { name: `Confirmar WorkSession ${closedOwnerId}` }).click();

  await expect(page.getByRole("alert")).toHaveText(
    "El estado cambió o la confirmación caducó. Actualiza y genera una nueva confirmación."
  );
  await expect(page.getByRole("group", { name: "Confirmar reconciliación del cierre" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Reconciliar cierre" })).toBeDisabled();
  await expectNoHorizontalOverflow(page);
  await retainScreenshot(page, `${viewport.name}-stale-confirmation.png`);
  expect(apiState.planRequests).toHaveLength(1);
  expect(apiState.confirmationRequests).toHaveLength(1);
  expect(apiState.recoveryRequests).toHaveLength(0);

  await page.getByRole("button", { name: "Actualizar" }).click();
  await expect(page.getByRole("button", { name: "Reconciliar cierre" })).toBeEnabled();
  await page.getByRole("button", { name: "Reconciliar cierre" }).click();
  await expect(page.getByRole("group", { name: "Confirmar reconciliación del cierre" })).toBeVisible();
  expect(apiState.planRequests).toHaveLength(2);
  expect(apiState.confirmationRequests).toHaveLength(1);
  expect(apiState.recoveryRequests).toHaveLength(0);

  await page.waitForTimeout(8_500);
  await expect(page.getByRole("group", { name: "Confirmar reconciliación del cierre" })).toBeVisible();
  expect(apiState.planRequests).toHaveLength(2);
});
}

for (const viewport of [
  { name: "desktop", width: 1440, height: 900 },
  { name: "mobile", width: 390, height: 844 }
] as const) {
  test(`${viewport.name} source advance offers one clear empty-session action`, async ({ page }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    const apiState: SyntheticState = {
      operatorRole: "PLATFORM_ADMINISTRATOR",
      operatorState: {
        surfaceEnabled: true,
        state: "SOURCE_ADVANCED",
        title: "Código actualizado",
        blocker: "El código canónico avanzó desde el intento fallido. AgentRun 96 permanece conservado y no se volverá a enviar ninguna instrucción ni adjunto automáticamente.",
        primaryAction: "START_FRESH_SESSION",
        primaryActionLabel: "Empezar desde código actual",
        primaryActionAvailable: true,
        requiredRole: "PLATFORM_ADMINISTRATOR",
        targetWorkSessionId: currentSessionId,
        targetAgentRunId: failedRunId
      },
      released: false,
      closeRequests: 0,
      planRequests: [],
      confirmationRequests: [],
      recoveryRequests: [],
      freshRequests: []
    };
    await openConversation(page, apiState, `${viewport.name}-source-advanced`);

    await expect(page.getByText("Código actualizado", { exact: true })).toBeVisible();
    await expect(page.getByText(
      "Se conservarán esta sesión y su historial. La nueva sesión se abrirá vacía y no iniciará Codex hasta que escribas una instrucción nueva.",
      { exact: true }
    )).toBeVisible();
    await expectPrimaryActionInFirstViewport(page, "Empezar desde código actual");
    await expectNoHorizontalOverflow(page);
    await retainScreenshot(page, `${viewport.name}-source-advanced.png`);

    await page.getByRole("button", { name: "Empezar desde código actual" }).click();
    await expect(page).toHaveURL(/#\/conversation\/1\/18$/);
    await expect(page.locator(".conversation-composer")).toBeVisible();
    expect(apiState.freshRequests).toHaveLength(1);
    expect(apiState.recoveryRequests).toHaveLength(0);
    expect(apiState.freshRequests![0]).toMatchObject({
      idempotencyKey: expect.stringMatching(
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
      )
    });
  });
}

test("fresh-session retry after transport loss reuses the same idempotency key", async ({ page }) => {
  const apiState: SyntheticState = {
    operatorRole: "PLATFORM_ADMINISTRATOR",
    operatorState: {
      surfaceEnabled: true,
      state: "SOURCE_ADVANCED",
      title: "Código actualizado",
      blocker: "El código canónico avanzó.",
      primaryAction: "START_FRESH_SESSION",
      primaryActionLabel: "Empezar desde código actual",
      primaryActionAvailable: true,
      requiredRole: "PLATFORM_ADMINISTRATOR",
      targetWorkSessionId: currentSessionId,
      targetAgentRunId: failedRunId
    },
    released: false,
    closeRequests: 0,
    planRequests: [],
    confirmationRequests: [],
    recoveryRequests: [],
    freshRequests: [],
    freshFailuresRemaining: 1
  };
  await openConversation(page, apiState, "fresh-response-loss");

  await page.getByRole("button", { name: "Empezar desde código actual" }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  await page.getByRole("button", { name: "Empezar desde código actual" }).click();
  await expect(page).toHaveURL(/#\/conversation\/1\/18$/);

  expect(apiState.freshRequests).toHaveLength(2);
  expect(apiState.freshRequests![0]).toEqual(apiState.freshRequests![1]);
  expect(apiState.recoveryRequests).toHaveLength(0);
});

test("blocked confirmation requires refresh and a new single-use plan", async ({ page }) => {
  const apiState: SyntheticState = {
    operatorRole: "PLATFORM_ADMINISTRATOR",
    operatorState: {
      surfaceEnabled: true,
      state: "REMOTE_CLOSE_BLOCKED",
      title: "Cierre remoto bloqueado",
      blocker: "No se liberó ningún recurso. Genera una nueva confirmación administrativa.",
      primaryAction: "RECONCILE_REMOTE_CLOSE",
      primaryActionLabel: "Volver a validar cierre",
      primaryActionAvailable: true,
      requiredRole: "PLATFORM_ADMINISTRATOR",
      targetWorkSessionId: closedOwnerId,
      targetAgentRunId: null
    },
    released: false,
    closeRequests: 0,
    planRequests: [],
    confirmationRequests: [],
    recoveryRequests: [],
    blockedConfirmation: true
  };
  await openConversation(page, apiState, "blocked-confirmation");

  await page.getByRole("button", { name: "Volver a validar cierre" }).click();
  await page.getByRole("button", { name: `Confirmar WorkSession ${closedOwnerId}` }).click();

  await expect(page.getByRole("group", { name: "Confirmar reconciliación del cierre" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Volver a validar cierre" })).toBeDisabled();
  await expect(page.getByRole("alert")).toHaveText(
    "La acción no pudo confirmarse. El estado se conserva; actualiza antes de volver a intentarlo."
  );
  expect(apiState.planRequests).toHaveLength(1);
  expect(apiState.confirmationRequests).toHaveLength(1);

  await page.getByRole("button", { name: "Actualizar" }).click();
  await expect(page.getByRole("button", { name: "Volver a validar cierre" })).toBeEnabled();
  await page.getByRole("button", { name: "Volver a validar cierre" }).click();
  await expect(page.getByRole("group", { name: "Confirmar reconciliación del cierre" })).toBeVisible();
  expect(apiState.planRequests).toHaveLength(2);
});

test("reconciling and unverifiable ownership never expose retry", async ({ page }) => {
  const apiState: SyntheticState = {
    operatorRole: "PLATFORM_ADMINISTRATOR",
    operatorState: {
      surfaceEnabled: true,
      state: "CLOSED_OWNER_RECONCILING",
      title: "Cierre remoto en reconciliación",
      blocker: "La sesión cerrada que retenía capacidad aún está confirmando su liberación.",
      primaryAction: "WAIT",
      primaryActionLabel: "Esperar actualización",
      primaryActionAvailable: false,
      requiredRole: null,
      targetWorkSessionId: closedOwnerId,
      targetAgentRunId: failedRunId
    },
    released: false,
    closeRequests: 0,
    planRequests: [],
    confirmationRequests: [],
    recoveryRequests: []
  };
  await openConversation(page, apiState, "reconciling-blocked");

  await expect(page.getByText("Esperando confirmación segura", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Reintentar", exact: true })).toHaveCount(0);

  apiState.operatorState = {
    ...apiState.operatorState,
    state: "REMOTE_CLOSE_BLOCKED",
    title: "Cierre remoto bloqueado",
    blocker: "La propiedad remota no pudo verificarse de forma segura.",
    primaryAction: "CONTACT_PLATFORM_ADMINISTRATOR",
    primaryActionLabel: "Contactar con administración",
    requiredRole: "PLATFORM_ADMINISTRATOR"
  };
  await page.getByRole("button", { name: "Actualizar" }).click();

  await expect(page.getByText("Cierre remoto bloqueado", { exact: true })).toBeVisible();
  await expect(page.getByText("La propiedad remota no pudo verificarse de forma segura.", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Contactar con administración" })).toBeDisabled();
  expect(apiState.recoveryRequests).toHaveLength(0);
});

for (const viewport of [
  { name: "desktop", width: 1440, height: 900 },
  { name: "mobile", width: 390, height: 844 }
] as const) {
  test(`${viewport.name} long blocker and legacy confirmation remain usable`, async ({ page }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    const longBlocker = "Otra sesión cerrada conserva la capacidad necesaria. La propiedad remota canónica debe verificarse antes de permitir cualquier reintento; responsabilidad-remota-canónica-sin-coincidencia-inequívoca seguirá bloqueada hasta completar la confirmación administrativa.";
    const apiState: SyntheticState = {
      operatorRole: "PLATFORM_ADMINISTRATOR",
      operatorState: {
        surfaceEnabled: true,
        state: "CLOSED_OWNER_BLOCKS_CAPACITY",
        title: "Bloqueada por una sesión cerrada",
        blocker: longBlocker,
        primaryAction: "RECONCILE_REMOTE_CLOSE",
        primaryActionLabel: "Reconciliar cierre",
        primaryActionAvailable: true,
        requiredRole: "PLATFORM_ADMINISTRATOR",
        targetWorkSessionId: closedOwnerId,
        targetAgentRunId: failedRunId
      },
      released: false,
      closeRequests: 0,
      planRequests: [],
      confirmationRequests: [],
      recoveryRequests: []
    };
    await openConversation(page, apiState, `${viewport.name}-long-confirmation`);

    await expect(page.getByText(longBlocker, { exact: true })).toBeVisible();
    await expectPrimaryActionInFirstViewport(page, "Reconciliar cierre");
    await expectNoHorizontalOverflow(page);
    await page.getByRole("button", { name: "Reconciliar cierre" }).click();

    const confirmation = page.getByRole("group", { name: "Confirmar reconciliación del cierre" });
    await expect(confirmation).toBeVisible();
    await expect(confirmation).toContainText("historial, Git, runs y adjuntos se conservan.");
    await expect(confirmation.getByText(`Objetivo: WorkSession ${closedOwnerId}`, { exact: true })).toBeVisible();
    await expect(confirmation.getByText(`Sesión abierta: WorkSession ${currentSessionId}. Solo se liberará el ownership remoto activo del objetivo; historial, Git, runs y adjuntos se conservan.`, { exact: true })).toBeVisible();
    const confirmButton = page.getByRole("button", { name: `Confirmar WorkSession ${closedOwnerId}` });
    await expect(confirmButton).toBeEnabled();
    const cancelButton = page.getByRole("button", { name: "Cancelar" });
    await expect(cancelButton).toBeEnabled();
    await expectNoHorizontalOverflow(page);
    const confirmBox = await confirmButton.boundingBox();
    const cancelBox = await cancelButton.boundingBox();
    const composerBox = await page.locator(".conversation-composer").boundingBox();
    const headerBox = await page.locator(".conversation-header").boundingBox();
    const stateTitleBox = await page.getByText("Bloqueada por una sesión cerrada", { exact: true }).boundingBox();
    expect(confirmBox).not.toBeNull();
    expect(cancelBox).not.toBeNull();
    expect(composerBox).not.toBeNull();
    expect(headerBox).not.toBeNull();
    expect(stateTitleBox).not.toBeNull();
    expect(confirmBox!.y + confirmBox!.height).toBeLessThanOrEqual(composerBox!.y);
    expect(cancelBox!.y + cancelBox!.height).toBeLessThanOrEqual(composerBox!.y);
    expect(stateTitleBox!.y).toBeGreaterThanOrEqual(headerBox!.y + headerBox!.height);
    await retainScreenshot(page, `${viewport.name}-legacy-confirmation.png`);

    expect(apiState.planRequests).toHaveLength(1);
    expect(apiState.confirmationRequests).toHaveLength(0);
    expect(apiState.recoveryRequests).toHaveLength(0);
  });
}

test("manual refresh projects released capacity without invoking an action", async ({ page }) => {
  const apiState: SyntheticState = {
    operatorRole: "PLATFORM_ADMINISTRATOR",
    operatorState: {
      surfaceEnabled: true,
      state: "CLOSED_OWNER_RECONCILING",
      title: "Cierre remoto en reconciliación",
      blocker: "La liberación exacta sigue pendiente de confirmar.",
      primaryAction: "WAIT",
      primaryActionLabel: "Esperar actualización",
      primaryActionAvailable: false,
      requiredRole: null,
      targetWorkSessionId: closedOwnerId,
      targetAgentRunId: failedRunId
    },
    released: false,
    closeRequests: 0,
    planRequests: [],
    confirmationRequests: [],
    recoveryRequests: []
  };
  await openConversation(page, apiState, "manual-refresh");
  await expect(page.getByText("Cierre remoto en reconciliación", { exact: true })).toBeVisible();

  apiState.released = true;
  await page.getByRole("button", { name: "Actualizar" }).click();

  await expect(page.getByText("Capacidad liberada", { exact: true })).toBeVisible();
  await expectPrimaryActionInFirstViewport(page, "Reintentar tarea");
  expect(apiState.closeRequests).toBe(0);
  expect(apiState.planRequests).toHaveLength(0);
  expect(apiState.confirmationRequests).toHaveLength(0);
  expect(apiState.recoveryRequests).toHaveLength(0);
});

test("mobile released capacity keeps the explicit retry in the first viewport", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const apiState: SyntheticState = {
    operatorRole: "ROUTINE_OPERATOR",
    operatorState: releasedState(),
    released: true,
    closeRequests: 0,
    planRequests: [],
    confirmationRequests: [],
    recoveryRequests: []
  };
  await openConversation(page, apiState, "mobile-capacity-released");

  await expect(page.getByText("Capacidad liberada", { exact: true })).toBeVisible();
  await expect(page.getByText("El reintento es una decisión explícita: no se ha vuelto a enviar ninguna instrucción.", { exact: true })).toBeVisible();
  await expect(page.locator(".conversation-header")).toBeVisible();
  expect(await page.evaluate(() => window.scrollY)).toBe(0);
  await expectPrimaryActionInFirstViewport(page, "Reintentar tarea");
  await expectNoHorizontalOverflow(page);
  await retainScreenshot(page, "mobile-capacity-released.png");
  expect(apiState.recoveryRequests).toHaveLength(0);
});
