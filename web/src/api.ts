import {
  ApiError,
  AuthSession,
  BillingQueueResponse,
  CoreCommandResponse,
  CoreCommandSummary,
  CoreScope,
  ManagedHost,
  MobileApiCostsOverview,
  MobileProjectOverview,
  MobileRescueConversation,
  MobileSessionEvents,
  MobileSessionSummary,
  MobileUpload,
  MobileWorkSessionConversation,
  OperationsHostStatus,
  OperationsIncident,
  ResolveMobileRescueSessionResult,
  ResolveMobileWorkSessionResult,
  SessionDeliverable,
  SessionDeliverablesView,
  WorkSessionAttachment,
  WorkSessionPreview
} from "./types";

const AUTH_KEY = "atenea.web.console.auth.v2";

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

  constructor() {
    this.session = readStoredSession();
  }

  get currentSession() {
    return this.session;
  }

  subscribe(listener: AuthListener) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  async login(email: string, password: string) {
    const session = await this.post<AuthSession>("/api/mobile/auth/login", { email, password }, false);
    this.setSession(session);
    return session;
  }

  async logout() {
    const refreshToken = this.session?.refreshToken;
    this.setSession(null);
    if (refreshToken) {
      try {
        await this.post("/api/mobile/auth/logout", { refreshToken }, false);
      } catch {
        // Local logout must remain deterministic even with an expired refresh token.
      }
    }
  }

  async me() {
    return this.get<{ operator: AuthSession["operator"] }>("/api/mobile/auth/me");
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

  workSessionPreview(sessionId: number) {
    return this.get<WorkSessionPreview>(`/api/mobile/sessions/${sessionId}/preview`);
  }

  async uploadWorkSessionAttachment(sessionId: number, file: File) {
    const form = new FormData();
    form.append("file", file);
    form.append("source", "OPERATOR_UPLOAD");
    form.append("kind", file.type.startsWith("image/") ? "IMAGE" : "FILE");
    form.append("retentionClass", "SESSION");
    return this.request<WorkSessionAttachment>(
      `/api/mobile/sessions/${sessionId}/attachments`,
      {
        method: "POST",
        body: form,
        authenticated: true,
        jsonBody: false,
        headers: { "Idempotency-Key": crypto.randomUUID() }
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

  private async refresh() {
    if (!this.session?.refreshToken) {
      return false;
    }
    try {
      const next = await this.post<AuthSession>("/api/mobile/auth/refresh", {
        refreshToken: this.session.refreshToken
      }, false);
      this.setSession(next);
      return true;
    } catch {
      this.setSession(null);
      return false;
    }
  }

  private setSession(session: AuthSession | null) {
    this.session = session;
    if (session) {
      window.sessionStorage.setItem(AUTH_KEY, JSON.stringify(session));
    } else {
      window.sessionStorage.removeItem(AUTH_KEY);
    }
    this.listeners.forEach((listener) => listener(session));
  }
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

function readStoredSession(): AuthSession | null {
  try {
    const raw = window.sessionStorage.getItem(AUTH_KEY);
    return raw ? JSON.parse(raw) as AuthSession : null;
  } catch {
    window.sessionStorage.removeItem(AUTH_KEY);
    return null;
  }
}

export const api = new AteneaApi();
