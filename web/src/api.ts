import {
  ApiError,
  AuthSession,
  BillingQueueResponse,
  CodexActivationAuthorization,
  CodexAdministratorInventory,
  CodexCatalog,
  CodexProgressReplay,
  CodexRecoveryAction,
  CodexRecoveryResponse,
  CodexRunDetail,
  CodexSettings,
  CodexRollbackAuthorization,
  CodexUpdateActivation,
  CodexUpdatePlan,
  CodexUpdateRollback,
  CodexUpdateStage,
  CoreCommandResponse,
  CoreCommandSummary,
  CoreScope,
  CreateWorkSessionTurnRequest,
  ManagedHost,
  LegacyRemoteCloseOperation,
  LegacyRemoteClosePlan,
  MobileApiCostsOverview,
  MobileProjectOverview,
  MobileRescueConversation,
  MobileSessionEvents,
  MobileSessionSummary,
  MobileUpload,
  MobileWorkSessionConversation,
  OperatorSessionInventory,
  OperationsHostStatus,
  OperationsIncident,
  ResolveMobileRescueSessionResult,
  ResolveMobileWorkSessionResult,
  SessionDeliverable,
  SessionDeliverablesView,
  StartFreshWorkSessionResult,
  UploadWorkSessionAttachmentRequest,
  WorkSessionAttachment,
  WorkSessionAttachmentCapability,
  WorkSessionPreview,
  TotpEnrollment,
  WebAuthnOptions
} from "./types";

const SESSION_PROTOCOL = "FAMILY_V1";
const SESSION_PROTOCOL_HEADER = "X-Atenea-Session-Protocol";
const SINGLE_FLIGHT_HEADER = "X-Atenea-Single-Flight";
const CSRF_HEADER = "X-Atenea-CSRF";

export type AuthListener = (session: AuthSession | null) => void;

type ApiRequestOptions = Omit<RequestInit, "body"> & {
  body?: unknown;
  authenticated?: boolean;
  jsonBody?: boolean;
};

type WorkSessionConversationEnvelope = {
  view: Omit<MobileWorkSessionConversation, "recentTurns">;
  recentTurns: MobileWorkSessionConversation["recentTurns"];
  recentTurnLimit: number;
  historyTruncated: boolean;
};

type CreateWorkSessionTurnEnvelope = {
  view: WorkSessionConversationEnvelope;
};

function unwrapWorkSessionConversation(
  response: WorkSessionConversationEnvelope
): MobileWorkSessionConversation {
  return {
    ...response.view,
    recentTurns: response.recentTurns
  };
}

export class AteneaApi {
  private session: AuthSession | null;
  private listeners = new Set<AuthListener>();
  private refreshPromise: Promise<boolean> | null = null;

  constructor() {
    this.session = null;
  }

  get currentSession() {
    return this.session;
  }

  subscribe(listener: AuthListener) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  async login(email: string, password: string) {
    const session = await this.request<AuthSession>("/api/web/auth/login", {
      method: "POST",
      body: { email, password, deviceLabel: "Atenea web" },
      authenticated: false,
      jsonBody: true,
      credentials: "include",
      headers: protocolHeaders()
    });
    this.setSession(session);
    return session;
  }

  async bootstrap() {
    if (this.session) return true;
    return this.refresh();
  }

  async logout() {
    try {
      await this.request("/api/web/auth/logout", {
        method: "POST",
        authenticated: false,
        credentials: "include",
        headers: cookieProofHeaders()
      });
    } catch {
      // Local logout remains deterministic if the server-side family already expired.
    } finally {
      this.setSession(null);
    }
  }

  async me() {
    return this.get<{ operator: AuthSession["operator"] }>("/api/mobile/auth/me");
  }

  sessions() {
    return this.get<OperatorSessionInventory[]>("/api/mobile/auth/sessions");
  }

  revokeSession(familyId: string) {
    return this.delete(`/api/mobile/auth/sessions/${encodeURIComponent(familyId)}`);
  }

  revokeOtherSessions() {
    return this.delete("/api/mobile/auth/sessions/others");
  }

  beginTotpEnrollment() {
    return this.post<TotpEnrollment>("/api/auth/totp/enrollments");
  }

  cancelTotpEnrollment(enrollmentId: string) {
    return this.delete(`/api/auth/totp/enrollments/${encodeURIComponent(enrollmentId)}`);
  }

  activateTotpEnrollment(enrollmentId: string, code: string) {
    return this.post<{ recoveryCodes: string[] }>("/api/auth/totp/enrollments/activate", {
      enrollmentId,
      code
    });
  }

  async registerPasskey() {
    requireWebAuthn();
    const options = await this.post<WebAuthnOptions>("/api/web/auth/webauthn/registration/options");
    const credential = await navigator.credentials.create({
      publicKey: registrationOptions(options)
    }) as PublicKeyCredential | null;
    if (!credential) throw new Error("El registro de passkey fue cancelado.");
    const response = credential.response as AuthenticatorAttestationResponse;
    await this.post("/api/web/auth/webauthn/registration", {
      requestId: options.requestId,
      credentialId: encodeBase64Url(credential.rawId),
      clientDataJson: encodeBase64Url(response.clientDataJSON),
      attestationObject: encodeBase64Url(response.attestationObject),
      transports: response.getTransports?.() ?? []
    });
  }

  async loginWithPasskey() {
    requireWebAuthn();
    const options = await this.request<WebAuthnOptions>("/api/web/auth/webauthn/authentication/options", {
      method: "POST",
      authenticated: false,
      credentials: "include"
    });
    const credential = await navigator.credentials.get({
      publicKey: authenticationOptions(options)
    }) as PublicKeyCredential | null;
    if (!credential) throw new Error("El acceso con passkey fue cancelado.");
    const response = credential.response as AuthenticatorAssertionResponse;
    if (!response.userHandle) throw new Error("La passkey no devolvió una identidad válida.");
    const session = await this.request<AuthSession>("/api/web/auth/webauthn/authentication", {
      method: "POST",
      body: {
        requestId: options.requestId,
        credentialId: encodeBase64Url(credential.rawId),
        userHandle: encodeBase64Url(response.userHandle),
        clientDataJson: encodeBase64Url(response.clientDataJSON),
        authenticatorData: encodeBase64Url(response.authenticatorData),
        signature: encodeBase64Url(response.signature),
        deviceLabel: "Atenea web"
      },
      authenticated: false,
      jsonBody: true,
      credentials: "include",
      headers: protocolHeaders()
    });
    this.setSession(session);
    return session;
  }

  async health() {
    return this.get<Record<string, unknown>>("/actuator/health", false);
  }

  async runCoreCommand(input: string, scope: CoreScope, projectId?: number | null, workSessionId?: number | null) {
    return this.post<CoreCommandResponse>("/api/core/commands", {
      input,
      channel: "TEXT",
      context: {
        projectId: projectId ?? null,
        workSessionId: workSessionId ?? null,
        operatorKey: null,
        scope
      },
      confirmation: {
        confirmed: false,
        confirmationToken: null
      }
    });
  }

  async confirmCoreCommand(commandId: number, confirmationToken: string) {
    return this.post<CoreCommandResponse>(`/api/core/commands/${commandId}/confirm`, { confirmationToken });
  }

  async coreHistory() {
    const response = await this.get<{ items: CoreCommandSummary[] }>("/api/core/commands");
    return response.items ?? [];
  }

  projects() {
    return this.get<MobileProjectOverview[]>("/api/mobile/projects/overview");
  }

  async resolveWorkSession(projectId: number, title?: string | null): Promise<ResolveMobileWorkSessionResult> {
    const response = await this.post<{
      created: boolean;
      view: WorkSessionConversationEnvelope;
    }>(`/api/mobile/projects/${projectId}/sessions/resolve`, {
      title: title || null
    });
    return {
      created: response.created,
      view: unwrapWorkSessionConversation(response.view)
    };
  }

  async workSessionSummary(sessionId: number): Promise<MobileSessionSummary> {
    const response = await this.get<
      Omit<MobileSessionSummary, "conversation"> & {
        conversation: WorkSessionConversationEnvelope;
      }
    >(`/api/mobile/sessions/${sessionId}/summary`);
    return {
      ...response,
      conversation: unwrapWorkSessionConversation(response.conversation)
    };
  }

  async workSessionConversation(sessionId: number) {
    const response = await this.get<WorkSessionConversationEnvelope>(
      `/api/mobile/sessions/${sessionId}/conversation`
    );
    return unwrapWorkSessionConversation(response);
  }

  async createWorkSessionTurn(sessionId: number, request: CreateWorkSessionTurnRequest) {
    const response = await this.post<CreateWorkSessionTurnEnvelope>(
      `/api/mobile/sessions/${sessionId}/turns`,
      {
        message: request.message,
        clientRequestId: request.clientRequestId,
        attachmentIds: [...request.attachmentIds]
      }
    );
    return unwrapWorkSessionConversation(response.view);
  }

  codexCatalog() {
    return this.get<CodexCatalog>("/api/codex/catalog");
  }

  projectCodexSettings(projectId: number) {
    return this.get<CodexSettings>(`/api/projects/${projectId}/codex-settings`);
  }

  sessionCodexSettings(sessionId: number) {
    return this.get<CodexSettings>(`/api/sessions/${sessionId}/codex-settings`);
  }

  updateSessionCodexSettings(
    sessionId: number,
    modelId: string,
    reasoningEffort: string,
    catalogRevision: string
  ) {
    return this.put<CodexSettings>(`/api/sessions/${sessionId}/codex-settings`, {
      modelId,
      reasoningEffort,
      catalogRevision,
      idempotencyKey: crypto.randomUUID()
    });
  }

  codexRunDetail(runId: number) {
    return this.get<CodexRunDetail>(`/api/runs/${runId}/codex-detail`);
  }

  codexRunProgress(runId: number, afterSequence = 0) {
    return this.get<CodexProgressReplay>(`/api/runs/${runId}/progress?afterSequence=${afterSequence}`);
  }

  requestCodexRecovery(runId: number, workSessionId: number, action: CodexRecoveryAction) {
    return this.post<CodexRecoveryResponse>(`/api/runs/${runId}/recovery`, {
      workSessionId,
      action,
      idempotencyKey: crypto.randomUUID()
    });
  }

  resumeWorkSessionClose(sessionId: number) {
    return this.post(`/api/sessions/${sessionId}/close`);
  }

  async startFreshWorkSession(
    sessionId: number,
    idempotencyKey: string
  ): Promise<StartFreshWorkSessionResult> {
    const response = await this.post<Omit<StartFreshWorkSessionResult, "view"> & {
      view: WorkSessionConversationEnvelope;
    }>(`/api/mobile/sessions/${sessionId}/start-fresh`, { idempotencyKey });
    return {
      ...response,
      view: unwrapWorkSessionConversation(response.view)
    };
  }

  createLegacyRemoteClosePlan(sessionId: number, idempotencyKey: string) {
    return this.post<LegacyRemoteClosePlan>(
      `/api/admin/work-sessions/${sessionId}/remote-close-plans`,
      {
        operation: "RECONCILE_REMOTE_CLOSE",
        idempotencyKey
      }
    );
  }

  confirmLegacyRemoteClose(
    sessionId: number,
    plan: LegacyRemoteClosePlan,
    idempotencyKey: string
  ) {
    return this.post<LegacyRemoteCloseOperation>(
      `/api/admin/work-sessions/${sessionId}/remote-close-reconciliations`,
      {
        operation: "RECONCILE_REMOTE_CLOSE",
        planId: plan.planId,
        ownershipFingerprintSha256: plan.ownershipFingerprintSha256,
        idempotencyKey
      }
    );
  }

  codexAdministratorInventory() {
    return this.get<CodexAdministratorInventory>("/api/admin/codex/inventory");
  }

  createCodexUpdatePlan(workerId: string) {
    return this.post<CodexUpdatePlan>("/api/admin/codex/update-plans", {
      operation: "PLAN_CODEX_UPDATE",
      workerId,
      idempotencyKey: crypto.randomUUID()
    });
  }

  stageCodexUpdate(plan: CodexUpdatePlan) {
    return this.post<CodexUpdateStage>("/api/admin/codex/update-stages", {
      operation: "STAGE_CODEX_UPDATE",
      planId: plan.planId,
      candidateId: plan.candidate.inventoryId,
      idempotencyKey: crypto.randomUUID()
    });
  }

  authorizeCodexActivation(plan: CodexUpdatePlan) {
    return this.post<CodexActivationAuthorization>(
      "/api/admin/codex/update-activation-authorizations",
      {
        operation: "AUTHORIZE_CODEX_UPDATE_ACTIVATION",
        planId: plan.planId,
        candidateId: plan.candidate.inventoryId,
        idempotencyKey: crypto.randomUUID()
      }
    );
  }

  activateCodexUpdate(plan: CodexUpdatePlan, authorizationId: string) {
    return this.post<CodexUpdateActivation>("/api/admin/codex/update-activations", {
      operation: "ACTIVATE_CODEX_UPDATE",
      planId: plan.planId,
      candidateId: plan.candidate.inventoryId,
      authorizationId,
      idempotencyKey: crypto.randomUUID()
    });
  }

  authorizeCodexRollback(activationId: string) {
    return this.post<CodexRollbackAuthorization>(
      "/api/admin/codex/update-rollback-authorizations",
      {
        operation: "AUTHORIZE_CODEX_UPDATE_ROLLBACK",
        activationId,
        idempotencyKey: crypto.randomUUID()
      }
    );
  }

  rollbackCodexUpdate(activationId: string, authorizationId: string) {
    return this.post<CodexUpdateRollback>("/api/admin/codex/update-rollbacks", {
      operation: "ROLLBACK_CODEX_UPDATE",
      activationId,
      authorizationId,
      idempotencyKey: crypto.randomUUID()
    });
  }

  sessionDeliverables(sessionId: number) {
    return this.get<SessionDeliverablesView>(`/api/mobile/sessions/${sessionId}/deliverables`);
  }

  sessionDeliverable(sessionId: number, deliverableId: number) {
    return this.get<SessionDeliverable>(`/api/mobile/sessions/${sessionId}/deliverables/${deliverableId}`);
  }

  sessionEvents(sessionId: number, limit = 80) {
    return this.get<MobileSessionEvents>(`/api/mobile/sessions/${sessionId}/events?limit=${limit}`);
  }

  resolveRescueSession(projectId: number, title = "Rescate operativo") {
    return this.post<ResolveMobileRescueSessionResult>(`/api/mobile/projects/${projectId}/rescue-sessions/resolve`, {
      title
    });
  }

  rescueConversation(sessionId: number) {
    return this.get<MobileRescueConversation>(`/api/mobile/rescue-sessions/${sessionId}/conversation`);
  }

  createRescueTurn(sessionId: number, message: string) {
    return this.post<{ view: MobileRescueConversation }>(`/api/mobile/rescue-sessions/${sessionId}/turns`, { message });
  }

  operationsHosts() {
    return this.get<ManagedHost[]>("/api/mobile/operations/hosts");
  }

  operationsHostStatus(hostId: number) {
    return this.get<OperationsHostStatus>(`/api/mobile/operations/hosts/${hostId}/status`);
  }

  operationsIncidents() {
    return this.get<{ incidents: OperationsIncident[] }>("/api/mobile/operations/incidents");
  }

  costsOverview(days = 30) {
    return this.get<MobileApiCostsOverview>(`/api/mobile/costs/overview?days=${days}`);
  }

  billingQueue() {
    return this.get<BillingQueueResponse>("/api/billing/queue");
  }

  billingQueueSummary() {
    return this.get<unknown>("/api/billing/queue/summary");
  }

  async upload(file: File) {
    const form = new FormData();
    form.append("file", file);
    return this.request<MobileUpload>("/api/mobile/uploads", {
      method: "POST",
      body: form,
      authenticated: true,
      jsonBody: false
    });
  }

  workSessionAttachments(sessionId: number) {
    return this.get<WorkSessionAttachment[]>(
      `/api/mobile/sessions/${sessionId}/attachments?limit=50`
    );
  }

  workSessionAttachmentCapability(sessionId: number) {
    return this.get<WorkSessionAttachmentCapability>(
      `/api/mobile/sessions/${sessionId}/attachments/capability`
    );
  }

  workSessionPreview(sessionId: number) {
    return this.get<WorkSessionPreview>(`/api/mobile/sessions/${sessionId}/preview`);
  }

  async uploadWorkSessionAttachment(sessionId: number, request: UploadWorkSessionAttachmentRequest) {
    const form = new FormData();
    form.append("file", request.file);
    form.append("source", "OPERATOR_UPLOAD");
    form.append("kind", request.file.type.startsWith("image/") ? "IMAGE" : "FILE");
    form.append("retentionClass", "SESSION");
    return this.request<WorkSessionAttachment>(
      `/api/mobile/sessions/${sessionId}/attachments`,
      {
        method: "POST",
        body: form,
        authenticated: true,
        jsonBody: false,
        headers: { "Idempotency-Key": request.idempotencyKey }
      }
    );
  }

  async downloadWorkSessionAttachment(sessionId: number, attachmentId: string) {
    const path = `/api/mobile/sessions/${sessionId}/attachments/${attachmentId}/content`;
    let response = await this.authenticatedFetch(path);
    if (response.status === 401 && await this.refresh()) {
      response = await this.authenticatedFetch(path);
    }
    if (response.status === 401) {
      this.setSession(null);
    }
    if (!response.ok) {
      await parseResponse<never>(response);
    }
    return response.blob();
  }

  get<T>(path: string, authenticated = true) {
    return this.request<T>(path, { method: "GET", authenticated });
  }

  post<T>(path: string, body?: unknown, authenticated = true) {
    return this.request<T>(path, { method: "POST", body, authenticated, jsonBody: true });
  }

  put<T>(path: string, body?: unknown, authenticated = true) {
    return this.request<T>(path, { method: "PUT", body, authenticated, jsonBody: true });
  }

  delete<T>(path: string, body?: unknown, authenticated = true) {
    return this.request<T>(path, { method: "DELETE", body, authenticated, jsonBody: true });
  }

  private async request<T>(
    path: string,
    options: ApiRequestOptions,
    retried = false
  ): Promise<T> {
    const headers = new Headers(options.headers);
    headers.set("Accept", "application/json");
    if (options.jsonBody !== false && options.body !== undefined) {
      headers.set("Content-Type", "application/json");
    }
    if (options.authenticated !== false && this.session?.accessToken) {
      headers.set("Authorization", `Bearer ${this.session.accessToken}`);
    }

    const response = await fetch(path, {
      ...options,
      headers,
      body: options.jsonBody !== false && options.body !== undefined
        ? JSON.stringify(options.body)
        : options.body as BodyInit | null | undefined
    });

    if (response.status === 401 && !retried && options.authenticated !== false && await this.refresh()) {
      return this.request<T>(path, options, true);
    }

    if (response.status === 401) {
      this.setSession(null);
    }

    return parseResponse<T>(response);
  }

  private authenticatedFetch(path: string) {
    const headers = new Headers({ Accept: "*/*" });
    if (this.session?.accessToken) {
      headers.set("Authorization", `Bearer ${this.session.accessToken}`);
    }
    return fetch(path, { method: "GET", headers });
  }

  private refresh() {
    if (!this.refreshPromise) {
      this.refreshPromise = this.refreshCookieSession().finally(() => {
        this.refreshPromise = null;
      });
    }
    return this.refreshPromise;
  }

  private async refreshCookieSession() {
    try {
      const next = await this.request<AuthSession>("/api/web/auth/refresh", {
        method: "POST",
        authenticated: false,
        credentials: "include",
        headers: cookieProofHeaders()
      });
      this.setSession(next);
      return true;
    } catch {
      this.setSession(null);
      return false;
    }
  }

  private setSession(session: AuthSession | null) {
    this.session = session;
    this.listeners.forEach((listener) => listener(session));
  }
}

function protocolHeaders() {
  return {
    [SESSION_PROTOCOL_HEADER]: SESSION_PROTOCOL,
    [SINGLE_FLIGHT_HEADER]: "true"
  };
}

function cookieProofHeaders() {
  const csrf = document.cookie
    .split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith("ATENEA_CSRF="))
    ?.slice("ATENEA_CSRF=".length);
  return {
    ...protocolHeaders(),
    ...(csrf ? { [CSRF_HEADER]: decodeURIComponent(csrf) } : {})
  };
}

function requireWebAuthn() {
  if (!window.isSecureContext || !("PublicKeyCredential" in window)) {
    throw new Error("Passkey no está disponible en este navegador seguro.");
  }
}

function registrationOptions(options: WebAuthnOptions): PublicKeyCredentialCreationOptions {
  if (!options.userHandle || !options.opaqueUserName || !options.relyingPartyName) {
    throw new Error("El servidor no devolvió opciones de registro válidas.");
  }
  return {
    challenge: decodeBase64Url(options.challenge),
    timeout: options.timeoutMillis,
    rp: { id: options.relyingPartyId, name: options.relyingPartyName },
    user: {
      id: decodeBase64Url(options.userHandle),
      name: options.opaqueUserName,
      displayName: options.opaqueUserName
    },
    pubKeyCredParams: options.credentialParameters.map((item) => ({
      type: item.type,
      alg: item.algorithm
    })),
    excludeCredentials: options.credentials.map(descriptor),
    authenticatorSelection: {
      userVerification: options.userVerification,
      residentKey: options.residentKey ?? undefined
    },
    attestation: options.attestation ?? "none"
  };
}

function authenticationOptions(options: WebAuthnOptions): PublicKeyCredentialRequestOptions {
  return {
    challenge: decodeBase64Url(options.challenge),
    timeout: options.timeoutMillis,
    rpId: options.relyingPartyId,
    allowCredentials: options.credentials.map(descriptor),
    userVerification: options.userVerification
  };
}

function descriptor(value: WebAuthnOptions["credentials"][number]): PublicKeyCredentialDescriptor {
  return { type: value.type, id: decodeBase64Url(value.id), transports: value.transports };
}

function decodeBase64Url(value: string) {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=");
  const binary = atob(padded);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

function encodeBase64Url(value: ArrayBuffer) {
  const bytes = new Uint8Array(value);
  let binary = "";
  bytes.forEach((byte) => { binary += String.fromCharCode(byte); });
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function parseResponse<T>(response: Response): Promise<T> {
  const contentType = response.headers.get("content-type") || "";
  const body = contentType.includes("application/json")
    ? await response.json().catch(() => null)
    : await response.text().catch(() => "");
  if (!response.ok) {
    const payload = body && typeof body === "object" ? body : undefined;
    const message = extractMessage(payload) || (typeof body === "string" && body) || `HTTP ${response.status}`;
    throw new ApiError(response.status, message, payload);
  }
  return (body === "" || body == null ? {} : body) as T;
}

function extractMessage(payload: unknown) {
  if (!payload || typeof payload !== "object") {
    return "";
  }
  const object = payload as Record<string, unknown>;
  return String(object.message || object.error || object.reason || "").trim();
}

export const api = new AteneaApi();
