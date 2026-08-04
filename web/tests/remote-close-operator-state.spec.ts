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
};

const currentSessionId = 641;
const closedOwnerId = 640;
const failedRunId = 9961;

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: "application/json",
    headers: { "Cache-Control": "no-store" },
    body: JSON.stringify(body)
  });
}

function conversationEnvelope() {
  return {
    view: {
      session: {
        id: currentSessionId,
        projectId: 1,
        title: "Validación sintética del cierre remoto",
        status: "OPEN",
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
    conversation: conversationEnvelope(),
    approvedDeliverables: { deliverables: [] },
    approvedPriceEstimate: null,
    actions: {},
    insights: {},
    operatorState: apiState.released ? releasedState() : apiState.operatorState
  };
}

async function installSyntheticApi(page: Page, apiState: SyntheticState) {
  await page.addInitScript((role) => {
    sessionStorage.setItem("atenea.web.console.auth.v2", JSON.stringify({
      accessToken: "synthetic-access",
      accessTokenExpiresAt: "2099-01-01T00:00:00Z",
      refreshToken: "synthetic-refresh",
      refreshTokenExpiresAt: "2099-01-01T00:00:00Z",
      operator: {
        id: 1,
        email: "operator@example.invalid",
        displayName: "Operador sintético",
        codexOperationsRole: role
      }
    }));
  }, apiState.operatorRole);

  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === `/api/mobile/sessions/${currentSessionId}/summary`) {
      return json(route, summary(apiState));
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
        workSessionId: closedOwnerId,
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
    if (path === "/api/mobile/operations/hosts") return json(route, []);
    if (path === "/api/mobile/operations/incidents") return json(route, { incidents: [] });
    return json(route, {});
  });
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
  expect(apiState.planRequests).toHaveLength(1);
  expect(apiState.confirmationRequests).toHaveLength(0);
  expect(apiState.recoveryRequests).toHaveLength(0);

  await page.getByRole("button", { name: "Confirmar reconciliación" }).click();
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
