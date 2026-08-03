import { expect, Page, Route, test } from "@playwright/test";

type BlockedReason =
  | "GLOBAL_DISABLED"
  | "PROJECT_DISABLED"
  | "SESSION_NOT_ELIGIBLE"
  | "OWNERSHIP_INVALID"
  | "SESSION_QUOTA_EXHAUSTED"
  | "WORKER_UNAVAILABLE"
  | "WORKER_UNSUPPORTED";

interface Capability {
  state: "READY" | "BLOCKED";
  blockedReason: "NONE" | BlockedReason;
  message: string;
  nextAction: string;
  policyRevision: string;
  workerCompatibility: "NOT_CHECKED" | "UNAVAILABLE" | "INCOMPATIBLE" | "COMPATIBLE";
  acceptedContentTypes: string[];
  currentSessionBytes: number;
  maxSessionBytes: number;
  remainingSessionBytes: number;
  maxFileBytes: number;
  maxAttachmentsPerTurn: number;
  maxAttachmentBytesPerTurn: number;
}

interface SyntheticTurn {
  id: number;
  actor: string;
  messageText: string;
  createdAt: string;
  attachments: ReturnType<typeof historicalAttachment>[];
}

interface ApiState {
  capability: Capability;
  uploads: Array<{ id: string; idempotencyKey: string; multipart: string }>;
  turnRequests: Array<{ message: string; clientRequestId: string; attachmentIds: string[] }>;
  turns: SyntheticTurn[];
  turnFailuresRemaining: number;
}

const validPng = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
  "base64"
);

const readyCapability: Capability = {
  state: "READY",
  blockedReason: "NONE",
  message: "Puedes añadir imágenes.",
  nextAction: "Añade una imagen o continúa con texto.",
  policyRevision: "synthetic-component-api-tests",
  workerCompatibility: "COMPATIBLE",
  acceptedContentTypes: ["image/png", "image/jpeg", "image/webp"],
  currentSessionBytes: 0,
  maxSessionBytes: 1024 * 1024,
  remainingSessionBytes: 1024 * 1024,
  maxFileBytes: 1024 * 1024,
  maxAttachmentsPerTurn: 4,
  maxAttachmentBytesPerTurn: 4 * 1024 * 1024
};

const blockedCases: Array<{
  reason: BlockedReason;
  title: string;
  message: string;
  nextAction: string;
}> = [
  {
    reason: "GLOBAL_DISABLED",
    title: "Solo texto",
    message: "Los adjuntos nuevos están desactivados.",
    nextAction: "Continúa con texto o contacta con un administrador."
  },
  {
    reason: "PROJECT_DISABLED",
    title: "Solo texto",
    message: "Los adjuntos no están habilitados para este proyecto.",
    nextAction: "Continúa con texto; solo las WorkSessions nuevas de Atenea pueden adjuntar."
  },
  {
    reason: "SESSION_NOT_ELIGIBLE",
    title: "Sesión sin imágenes",
    message: "Esta sesión se creó antes de habilitar los adjuntos.",
    nextAction: "Crea una WorkSession nueva de Atenea."
  },
  {
    reason: "OWNERSHIP_INVALID",
    title: "Sesión no válida para imágenes",
    message: "La sesión no tiene ownership canónico completo.",
    nextAction: "Cierra esta sesión y crea una WorkSession limpia."
  },
  {
    reason: "SESSION_QUOTA_EXHAUSTED",
    title: "Cuota de imágenes agotada",
    message: "La sesión ha agotado su cuota de adjuntos.",
    nextAction: "Continúa con texto; los adjuntos no se eliminan automáticamente."
  },
  {
    reason: "WORKER_UNAVAILABLE",
    title: "Imágenes temporalmente no disponibles",
    message: "El almacenamiento de adjuntos no está disponible.",
    nextAction: "Reintenta cuando AX42 vuelva a estar accesible."
  },
  {
    reason: "WORKER_UNSUPPORTED",
    title: "AX42 necesita actualizarse",
    message: "El worker no admite la versión actual de adjuntos.",
    nextAction: "Actualiza o recupera el servicio de adjuntos antes de reintentar."
  }
];

function state(capability: Capability = readyCapability): ApiState {
  return {
    capability: { ...capability },
    uploads: [],
    turnRequests: [],
    turns: [],
    turnFailuresRemaining: 0
  };
}

function blockedCapability(testCase: (typeof blockedCases)[number]): Capability {
  return {
    ...readyCapability,
    state: "BLOCKED",
    blockedReason: testCase.reason,
    message: testCase.message,
    nextAction: testCase.nextAction,
    workerCompatibility: testCase.reason === "WORKER_UNAVAILABLE"
      ? "UNAVAILABLE"
      : testCase.reason === "WORKER_UNSUPPORTED" ? "INCOMPATIBLE" : "NOT_CHECKED"
  };
}

function historicalAttachment(id: string, position: number, filename = `image-${position}.png`) {
  return {
    id,
    position,
    originalFilename: filename,
    contentType: "image/png",
    sizeBytes: validPng.length,
    sha256: "0".repeat(64),
    downloadPath: `/api/mobile/sessions/41/attachments/${id}/content`
  };
}

function conversationEnvelope(turns: SyntheticTurn[]) {
  return {
    view: {
      session: {
        id: 41,
        projectId: 1,
        title: "Synthetic attachment component test",
        status: "ACTIVE",
        operationalState: "READY",
        closeRetryable: false
      },
      runInProgress: false,
      canCreateTurn: true,
      latestRun: null,
      lastError: null,
      lastAgentResponse: null
    },
    recentTurns: turns,
    recentTurnLimit: 50,
    historyTruncated: false
  };
}

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: "application/json",
    headers: { "Cache-Control": "no-store" },
    body: JSON.stringify(body)
  });
}

async function installSyntheticApi(page: Page, apiState: ApiState) {
  await page.addInitScript(() => {
    sessionStorage.setItem("atenea.web.console.auth.v2", JSON.stringify({
      accessToken: "synthetic-access",
      accessTokenExpiresAt: "2099-01-01T00:00:00Z",
      refreshToken: "synthetic-refresh",
      refreshTokenExpiresAt: "2099-01-01T00:00:00Z",
      operator: { id: 1, email: "operator@example.invalid", displayName: "Operador" }
    }));
  });

  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path === "/api/mobile/sessions/41/conversation") {
      return json(route, conversationEnvelope(apiState.turns));
    }
    if (path === "/api/mobile/sessions/41/attachments/capability") {
      return json(route, apiState.capability);
    }
    if (path === "/api/mobile/sessions/41/attachments" && request.method() === "POST") {
      const uploadNumber = apiState.uploads.length + 1;
      const id = `00000000-0000-4000-8000-${String(uploadNumber).padStart(12, "0")}`;
      const multipart = request.postDataBuffer()?.toString("latin1") || "";
      apiState.uploads.push({
        id,
        idempotencyKey: request.headers()["idempotency-key"] || "",
        multipart
      });
      return json(route, {
        id,
        workSessionId: 41,
        projectId: 1,
        source: "OPERATOR_UPLOAD",
        kind: "IMAGE",
        originalFilename: `synthetic-${uploadNumber}.png`,
        contentType: "image/png",
        sizeBytes: validPng.length,
        retentionClass: "SESSION",
        retainUntil: "2099-01-01T00:00:00Z",
        sha256: "0".repeat(64),
        createdAt: "2026-08-02T00:00:00Z",
        indexedAt: "2026-08-02T00:00:00Z"
      }, 201);
    }
    if (path === "/api/mobile/sessions/41/turns" && request.method() === "POST") {
      const body = request.postDataJSON() as ApiState["turnRequests"][number];
      apiState.turnRequests.push(body);
      if (apiState.turnFailuresRemaining > 0) {
        apiState.turnFailuresRemaining -= 1;
        return json(route, { message: "Synthetic uncertain result." }, 504);
      }
      const accepted: SyntheticTurn = {
        id: 900 + apiState.turnRequests.length,
        actor: "OPERATOR",
        messageText: body.message,
        createdAt: "2026-08-02T00:00:00Z",
        attachments: body.attachmentIds.map((id, index) => historicalAttachment(id, index + 1))
      };
      apiState.turns = [...apiState.turns, accepted];
      return json(route, { view: conversationEnvelope(apiState.turns) }, 201);
    }
    if (path === "/api/codex/catalog") {
      return json(route, {
        workerId: "synthetic-worker",
        catalogRevision: "synthetic",
        schemaVersion: "1",
        codexVersion: "synthetic",
        generatedAt: "2026-08-02T00:00:00Z",
        observedAt: "2026-08-02T00:00:00Z",
        models: [{
          modelId: "synthetic-model",
          displayName: "Modelo operativo",
          defaultEffort: "high",
          availability: "AVAILABLE",
          efforts: ["high"]
        }]
      });
    }
    if (path === "/api/sessions/41/codex-settings") {
      return json(route, { scope: "WORK_SESSION", id: 41, modelId: "synthetic-model", reasoningEffort: "high" });
    }
    if (path === "/api/projects/1/codex-settings") {
      return json(route, { scope: "PROJECT", id: 1, modelId: null, reasoningEffort: null });
    }
    if (path === "/api/mobile/operations/hosts") {
      return json(route, []);
    }
    if (path === "/api/mobile/operations/incidents") {
      return json(route, { incidents: [] });
    }
    return json(route, {});
  });
}

async function openConversation(page: Page, apiState: ApiState, caseName: string) {
  await installSyntheticApi(page, apiState);
  await page.goto(`/?case=${encodeURIComponent(caseName)}#/conversation/1/41`, { waitUntil: "networkidle" });
  await expect(page.getByLabel("Perfil de próxima ejecución")).toContainText("Perfil listo");
}

function syntheticPng(name: string) {
  return { name, mimeType: "image/png", buffer: validPng };
}

async function pasteImages(page: Page, names: string[]) {
  await page.locator("textarea").evaluate((textarea, fileNames) => {
    const bytes = Uint8Array.from(atob(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    ), (character) => character.charCodeAt(0));
    const transfer = new DataTransfer();
    fileNames.forEach((name) => transfer.items.add(new File([bytes], name, { type: "image/png" })));
    textarea.dispatchEvent(new ClipboardEvent("paste", {
      bubbles: true,
      cancelable: true,
      clipboardData: transfer
    }));
  }, names);
}

test("picker uploads through the governed API and selects the image automatically", async ({ page }) => {
  const apiState = state();
  await openConversation(page, apiState, "picker");

  await page.getByLabel("Seleccionar imágenes").setInputFiles(syntheticPng("picker.png"));

  await expect(page.getByText("1 imagen lista", { exact: true })).toBeVisible();
  await expect(page.getByRole("list", { name: "Imágenes seleccionadas" })).toContainText("picker.png");
  expect(apiState.uploads).toHaveLength(1);
  expect(apiState.uploads[0].idempotencyKey).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  expect(apiState.uploads[0].multipart).toContain('name="source"');
  expect(apiState.uploads[0].multipart).toContain("OPERATOR_UPLOAD");
  expect(apiState.uploads[0].multipart).toContain('name="retentionClass"');
  expect(apiState.uploads[0].multipart).toContain("SESSION");
});

test("clipboard paste preserves upload and submitted attachment order", async ({ page }) => {
  const apiState = state();
  await openConversation(page, apiState, "paste-order");

  await pasteImages(page, ["first.png", "second.png"]);
  await expect(page.getByText("2 imágenes listas", { exact: true })).toBeVisible();
  await page.locator("textarea").fill("synthetic ordered turn");
  await page.getByRole("button", { name: "Enviar" }).click();

  await expect.poll(() => apiState.turnRequests.length).toBe(1);
  expect(apiState.uploads.map((upload) => upload.id)).toEqual(apiState.turnRequests[0].attachmentIds);
  expect(apiState.turnRequests[0].attachmentIds).toEqual([
    "00000000-0000-4000-8000-000000000001",
    "00000000-0000-4000-8000-000000000002"
  ]);
});

test("removal excludes only the exact selected image from submission", async ({ page }) => {
  const apiState = state();
  await openConversation(page, apiState, "remove");
  const picker = page.getByLabel("Seleccionar imágenes");
  await picker.setInputFiles(syntheticPng("first.png"));
  await expect(page.getByText("1 imagen lista", { exact: true })).toBeVisible();
  await picker.setInputFiles(syntheticPng("second.png"));
  await expect(page.getByText("2 imágenes listas", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "Quitar first.png" }).click();
  await expect(page.getByRole("button", { name: "Quitar first.png" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Quitar second.png" })).toBeVisible();
  await page.locator("textarea").fill("synthetic removal turn");
  await page.getByRole("button", { name: "Enviar" }).click();

  await expect.poll(() => apiState.turnRequests.length).toBe(1);
  expect(apiState.turnRequests[0].attachmentIds).toEqual(["00000000-0000-4000-8000-000000000002"]);
});

test("uncertain submission retains identity and selection until exact acceptance", async ({ page }) => {
  const apiState = state();
  apiState.turnFailuresRemaining = 1;
  await openConversation(page, apiState, "retry-clear");
  await page.getByLabel("Seleccionar imágenes").setInputFiles(syntheticPng("retry.png"));
  await expect(page.getByText("1 imagen lista", { exact: true })).toBeVisible();
  await page.locator("textarea").fill("synthetic retry turn");

  await page.getByRole("button", { name: "Enviar" }).click();
  await expect(page.getByText("Synthetic uncertain result.", { exact: true })).toBeVisible();
  await expect(page.locator("textarea")).toHaveValue("synthetic retry turn");
  await expect(page.getByRole("list", { name: "Imágenes seleccionadas" })).toBeVisible();
  await page.getByRole("button", { name: "Reintentar envío" }).click();

  await expect.poll(() => apiState.turnRequests.length).toBe(2);
  expect(apiState.turnRequests[1]).toEqual(apiState.turnRequests[0]);
  await expect(page.locator("textarea")).toHaveValue("");
  await expect(page.getByRole("list", { name: "Imágenes seleccionadas" })).toHaveCount(0);
  await expect(page.getByText("Imágenes disponibles", { exact: true })).toBeVisible();
});

test("historical reload renders bindings only on their exact turn", async ({ page }) => {
  const apiState = state();
  apiState.turns = [
    {
      id: 501,
      actor: "OPERATOR",
      messageText: "",
      createdAt: "2026-08-02T00:00:00Z",
      attachments: [
        historicalAttachment("10000000-0000-4000-8000-000000000001", 1, "owner-first.png"),
        historicalAttachment("10000000-0000-4000-8000-000000000002", 2, "owner-second.png")
      ]
    },
    {
      id: 502,
      actor: "CODEX",
      messageText: "",
      createdAt: "2026-08-02T00:00:01Z",
      attachments: []
    },
    {
      id: 503,
      actor: "OPERATOR",
      messageText: "",
      createdAt: "2026-08-02T00:00:02Z",
      attachments: []
    }
  ];
  await openConversation(page, apiState, "history");

  await expect(page.getByRole("list", { name: "Imágenes del turno 501" })).toHaveCount(1);
  await expect(page.getByRole("list", { name: "Imágenes del turno 501" }).getByRole("button")).toHaveCount(2);
  await expect(page.getByRole("list", { name: "Imágenes del turno 502" })).toHaveCount(0);
  await expect(page.getByRole("list", { name: "Imágenes del turno 503" })).toHaveCount(0);

  await page.reload({ waitUntil: "networkidle" });
  await expect(page.getByRole("list", { name: "Imágenes del turno 501" })).toContainText("owner-first.png");
  await expect(page.getByRole("list", { name: "Imágenes del turno 501" })).toContainText("owner-second.png");
  await expect(page.locator(".turn-attachment-list")).toHaveCount(1);
});

for (const testCase of blockedCases) {
  test(`blocked capability ${testCase.reason} is fail-closed and actionable`, async ({ page }) => {
    const apiState = state(blockedCapability(testCase));
    await openConversation(page, apiState, `blocked-${testCase.reason}`);

    await expect(page.getByText(testCase.title, { exact: true })).toBeVisible();
    await expect(page.getByText(`${testCase.message} ${testCase.nextAction}`, { exact: true })).toBeVisible();
    await expect(page.getByLabel("Seleccionar imágenes")).toHaveCount(0);
    await expect(page.getByLabel("Estado de imágenes del mensaje")).toHaveClass(/attachment-composer-state--blocked/);
    expect(apiState.uploads).toHaveLength(0);
  });
}

test("unsupported image type fails before the upload API", async ({ page }) => {
  const apiState = state();
  await openConversation(page, apiState, "unsupported-type");

  await page.getByLabel("Seleccionar imágenes").setInputFiles({
    name: "unsupported.gif",
    mimeType: "image/gif",
    buffer: Buffer.from("synthetic")
  });

  await expect(page.getByText("Usa una imagen PNG, JPEG o WebP.", { exact: true })).toBeVisible();
  expect(apiState.uploads).toHaveLength(0);
});

test("over-file validation fails before the upload API", async ({ page }) => {
  const apiState = state({ ...readyCapability, maxFileBytes: validPng.length - 1 });
  await openConversation(page, apiState, "over-file");

  await page.getByLabel("Seleccionar imágenes").setInputFiles(syntheticPng("over-file.png"));

  await expect(page.getByLabel("Estado de imágenes del mensaje"))
    .toContainText(`La imagen supera el máximo de ${validPng.length - 1} B`);
  expect(apiState.uploads).toHaveLength(0);
});

test("over-turn count retains the accepted selection and performs no extra upload", async ({ page }) => {
  const apiState = state({ ...readyCapability, maxAttachmentsPerTurn: 1 });
  await openConversation(page, apiState, "over-count");
  const picker = page.getByLabel("Seleccionar imágenes");
  await picker.setInputFiles(syntheticPng("first.png"));
  await expect(page.getByText("1 imagen lista", { exact: true })).toBeVisible();
  await picker.setInputFiles(syntheticPng("second.png"));

  await expect(page.getByText("Puedes seleccionar hasta 1 imagen por mensaje.", { exact: false })).toBeVisible();
  await expect(page.getByRole("button", { name: "Quitar first.png" })).toBeVisible();
  expect(apiState.uploads).toHaveLength(1);
});

test("over-turn bytes retain the first accepted selection", async ({ page }) => {
  const apiState = state({ ...readyCapability, maxAttachmentBytesPerTurn: validPng.length + 1 });
  await openConversation(page, apiState, "over-turn-bytes");
  const picker = page.getByLabel("Seleccionar imágenes");
  await picker.setInputFiles(syntheticPng("first.png"));
  await expect(page.getByText("1 imagen lista", { exact: true })).toBeVisible();
  await picker.setInputFiles(syntheticPng("second.png"));

  await expect(page.getByLabel("Estado de imágenes del mensaje"))
    .toContainText("Las imágenes del mensaje superan");
  await expect(page.getByRole("button", { name: "Quitar first.png" })).toBeVisible();
  expect(apiState.uploads).toHaveLength(1);
});

test("remaining session quota fails before the upload API", async ({ page }) => {
  const apiState = state({ ...readyCapability, remainingSessionBytes: validPng.length - 1 });
  await openConversation(page, apiState, "remaining-quota");

  await page.getByLabel("Seleccionar imágenes").setInputFiles(syntheticPng("quota.png"));

  await expect(page.getByText("La sesión no tiene cuota suficiente para esta imagen.", { exact: true })).toBeVisible();
  expect(apiState.uploads).toHaveLength(0);
});
