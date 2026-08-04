export type CoreScope = "GLOBAL" | "PROJECT" | "SESSION";

export interface OperatorProfile {
  id: number;
  email: string;
  displayName: string;
  codexOperationsRole?: "ROUTINE_OPERATOR" | "PRIVILEGED_OPERATOR" | "PLATFORM_ADMINISTRATOR";
}

export interface AuthSession {
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
  operator: OperatorProfile;
}

export interface ApiErrorPayload {
  message?: string;
  error?: string;
  state?: string;
  reason?: string;
  action?: string;
  retryable?: boolean;
}

export class ApiError extends Error {
  status: number;
  payload: ApiErrorPayload | unknown;

  constructor(status: number, message: string, payload?: ApiErrorPayload | unknown) {
    super(message);
    this.status = status;
    this.payload = payload;
  }
}

export interface CoreCommandResponse {
  commandId: number;
  status: string;
  operatorMessage?: string | null;
  speakableMessage?: string | null;
  confirmation?: {
    confirmationToken: string;
    message?: string | null;
  } | null;
  clarification?: {
    message?: string | null;
    options: CoreClarificationOption[];
  } | null;
  rawInput?: string | null;
  resultSummary?: string | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  createdAt?: string | null;
  finishedAt?: string | null;
}

export interface CoreClarificationOption {
  type: string;
  targetId?: number | null;
  label: string;
}

export interface CoreCommandSummary {
  commandId: number;
  status: string;
  rawInput: string;
  operatorMessage?: string | null;
  speakableMessage?: string | null;
  resultSummary?: string | null;
  errorMessage?: string | null;
  createdAt?: string | null;
}

export interface MobileProjectOverview {
  projectId: number;
  projectName: string;
  description?: string | null;
  defaultBaseBranch?: string | null;
  session?: MobileProjectSessionOverview | null;
}

export interface MobileProjectSessionOverview {
  sessionId: number;
  status: string;
  title: string;
  runInProgress: boolean;
  closeBlockedState?: string | null;
  pullRequestStatus?: string | null;
  lastActivityAt?: string | null;
}

export interface ResolveMobileWorkSessionResult {
  created: boolean;
  view: MobileWorkSessionConversation;
}

export interface MobileWorkSessionConversation {
  session: MobileWorkSession;
  runInProgress: boolean;
  canCreateTurn: boolean;
  latestRun?: MobileAgentRun | null;
  lastError?: string | null;
  lastAgentResponse?: string | null;
  recentTurns: MobileConversationTurn[];
}

export interface MobileWorkSession {
  id: number;
  projectId?: number | null;
  title: string;
  status: string;
  operationalState: string;
  baseBranch?: string | null;
  workspaceBranch?: string | null;
  pullRequestUrl?: string | null;
  pullRequestStatus?: string | null;
  finalCommitSha?: string | null;
  openedAt?: string | null;
  lastActivityAt?: string | null;
  publishedAt?: string | null;
  closedAt?: string | null;
  closeBlockedState?: string | null;
  closeBlockedReason?: string | null;
  closeBlockedAction?: string | null;
  closeRetryable: boolean;
}

export interface MobileAgentRun {
  id: number;
  status: string;
  startedAt?: string | null;
  finishedAt?: string | null;
  outputSummary?: string | null;
  errorSummary?: string | null;
}

export interface CodexCatalogModel {
  modelId: string;
  displayName: string;
  defaultEffort: string;
  availability: "AVAILABLE" | "DEPRECATED" | "BLOCKED";
  efforts: string[];
}

export interface CodexCatalog {
  workerId: string;
  catalogRevision: string;
  schemaVersion: string;
  codexVersion: string;
  generatedAt: string;
  observedAt: string;
  models: CodexCatalogModel[];
}

export interface CodexSettings {
  scope: "PROJECT" | "WORK_SESSION";
  id: number;
  modelId?: string | null;
  reasoningEffort?: string | null;
}

export interface CodexReleaseInventory {
  inventoryId: string;
  codexVersion: string;
  releaseDigestSha256: string;
  installationState: string;
  linkState: "CURRENT" | "PREVIOUS" | "NONE";
  compatibilityState: string;
  catalogRevision: string;
  observedAt: string;
}

export interface CodexWorkerInventory {
  workerId: string;
  protocolVersion: string;
  enabled: boolean;
  healthy: boolean;
  catalogRevision?: string | null;
  catalogCodexVersion?: string | null;
  observedAt?: string | null;
  installedVersions: string[];
  currentVersion?: string | null;
  previousVersion?: string | null;
  compatibilityState: string;
  releases: CodexReleaseInventory[];
}

export interface CodexAdministratorInventory {
  profilesEnabled: boolean;
  progressEnabled: boolean;
  recoveryEnabled: boolean;
  notificationOutboxEnabled: boolean;
  managedUpdatesEnabled: boolean;
  workers: CodexWorkerInventory[];
}

export interface CodexCompatibilityGate {
  gate: string;
  state: string;
}

export interface CodexUpdatePlan {
  planId: string;
  workerId: string;
  state: string;
  compatibilityState: string;
  current: CodexReleaseInventory;
  previous?: CodexReleaseInventory | null;
  candidate: CodexReleaseInventory;
  gates: CodexCompatibilityGate[];
  expectedServiceImpact: string;
  createdAt: string;
}

export interface CodexUpdateStage {
  stageId: string;
  planId: string;
  workerId: string;
  state: string;
  current: CodexReleaseInventory;
  previous?: CodexReleaseInventory | null;
  candidate: CodexReleaseInventory;
  gates: CodexCompatibilityGate[];
  linksChanged: boolean;
  valuesExposed: boolean;
  createdAt: string;
  completedAt: string;
}

export interface CodexActivationAuthorization {
  authorizationId: string;
  planId: string;
  workerId: string;
  currentInventoryId: string;
  candidateInventoryId: string;
  expiresAt: string;
  consumedAt?: string | null;
  consumedActivationId?: string | null;
  createdAt: string;
  automaticRestoreAuthorized: boolean;
}

export interface CodexUpdateActivation {
  activationId: string;
  authorizationId: string;
  planId: string;
  workerId: string;
  state: string;
  current: CodexReleaseInventory;
  previous: CodexReleaseInventory;
  candidate: CodexReleaseInventory;
  gates: CodexCompatibilityGate[];
  automaticRestore: string;
  valuesExposed: boolean;
  createdAt: string;
  completedAt: string;
}

export interface CodexRollbackAuthorization {
  authorizationId: string;
  activationId: string;
  planId: string;
  workerId: string;
  currentInventoryId: string;
  previousInventoryId: string;
  expiresAt: string;
  consumedAt?: string | null;
  consumedRollbackId?: string | null;
  createdAt: string;
}

export interface CodexUpdateRollback {
  rollbackId: string;
  authorizationId: string;
  activationId: string;
  planId: string;
  workerId: string;
  state: string;
  current: CodexReleaseInventory;
  previous: CodexReleaseInventory;
  linkRestore: string;
  workerServiceRestart: string;
  affectedServices: string[];
  appServerServicesRestarted: number;
  valuesExposed: boolean;
  createdAt: string;
  completedAt: string;
}

export interface CodexRunDetail {
  runId: number;
  workSessionId: number;
  status: string;
  modelId?: string | null;
  modelSource?: string | null;
  reasoningEffort?: string | null;
  effortSource?: string | null;
  catalogRevision?: string | null;
  codexVersion?: string | null;
  currentState?: string | null;
  latestSequence: number;
  retainedFloor: number;
  elapsedMillis: number;
  requiredNextAction?: string | null;
  retryOfRunId?: number | null;
}

export interface CodexProgressEvent {
  sequence: number;
  category: string;
  message: string;
  occurredAt: string;
}

export interface CodexProgressReplay {
  requestedAfterSequence: number;
  retainedFloor: number;
  cursorWasBelowRetainedFloor: boolean;
  currentState?: string | null;
  latestEvent?: CodexProgressEvent | null;
  terminalOutcome?: string | null;
  elapsedMillis: number;
  requiredNextAction?: string | null;
  events: CodexProgressEvent[];
}

export type CodexRecoveryAction = "CANCEL" | "RETRY" | "RECONCILE";

export interface CodexRecoveryResponse {
  operationId: string;
  state: string;
  action: CodexRecoveryAction;
  outcome?: string | null;
  summary?: string | null;
  requiredNextAction?: string | null;
  resultAgentRunId?: number | null;
}

export interface MobileConversationTurn {
  id: number;
  actor: string;
  messageText: string;
  createdAt?: string | null;
  executionProfile?: TurnExecutionProfile | null;
  attachments: SessionTurnAttachment[];
}

export interface SessionTurnAttachment {
  id: string;
  position: number;
  originalFilename: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  downloadPath: string;
}

export interface CreateWorkSessionTurnRequest {
  message: string;
  clientRequestId: string;
  attachmentIds: string[];
}

export type WorkSessionAttachmentCapabilityState = "READY" | "BLOCKED";

export type WorkSessionAttachmentBlockedReason =
  | "NONE"
  | "GLOBAL_DISABLED"
  | "PROJECT_DISABLED"
  | "SESSION_NOT_ELIGIBLE"
  | "OWNERSHIP_INVALID"
  | "SESSION_QUOTA_EXHAUSTED"
  | "WORKER_UNAVAILABLE"
  | "WORKER_UNSUPPORTED";

export type WorkSessionAttachmentWorkerCompatibility =
  | "NOT_CHECKED"
  | "UNAVAILABLE"
  | "INCOMPATIBLE"
  | "COMPATIBLE";

export interface WorkSessionAttachmentCapability {
  state: WorkSessionAttachmentCapabilityState;
  blockedReason: WorkSessionAttachmentBlockedReason;
  message: string;
  nextAction: string;
  policyRevision: string;
  workerCompatibility: WorkSessionAttachmentWorkerCompatibility;
  acceptedContentTypes: string[];
  currentSessionBytes: number;
  maxSessionBytes: number;
  remainingSessionBytes: number;
  maxFileBytes: number;
  maxAttachmentsPerTurn: number;
  maxAttachmentBytesPerTurn: number;
}

export interface UploadWorkSessionAttachmentRequest {
  file: File;
  idempotencyKey: string;
}

export interface TurnExecutionProfile {
  runId: number;
  modelId: string;
  modelSource: string;
  reasoningEffort: string;
  effortSource: string;
  codexVersion: string;
}

export interface WorkSessionAttachment {
  id: string;
  workSessionId: number;
  projectId: number;
  agentRunId?: number | null;
  source: "OPERATOR_UPLOAD" | "BROWSER_SCREENSHOT" | "BROWSER_TRACE" | "REPORT";
  kind: "IMAGE" | "TRACE" | "REPORT" | "FILE";
  originalFilename: string;
  contentType: string;
  sizeBytes: number;
  retentionClass: "TRANSIENT" | "SESSION" | "EVIDENCE";
  retainUntil: string;
  sha256: string;
  createdAt: string;
  indexedAt: string;
}

export interface WorkSessionPreview {
  id: string;
  workSessionId: number;
  projectId: number;
  agentRunId?: number | null;
  state: "STARTING" | "READY" | "BLOCKED" | "RECONCILING" | "STOPPED" | "EXPIRED";
  lifecycleRevision: number;
  privateUrl?: string | null;
  localhostCompatible: boolean;
  leaseExpiresAt: string;
  hardExpiresAt: string;
  auditRetainUntil: string;
  failureReason?: string | null;
  nextAction: string;
  primaryAction: "NONE" | "WAIT" | "OPEN" | "START";
}

export interface MobileSessionSummary {
  conversation: MobileWorkSessionConversation;
  approvedDeliverables: SessionDeliverablesView;
  approvedPriceEstimate?: ApprovedPriceEstimateSummary | null;
  actions: MobileSessionActions;
  insights: MobileSessionInsights;
  operatorState: MobileSessionOperatorState;
}

export interface MobileSessionOperatorState {
  surfaceEnabled: boolean;
  state: "DEFAULT" | "RUNNING" | "CLOSING_REMOTE" | "REMOTE_CLOSE_BLOCKED" |
    "LEGACY_CLOSE_REQUIRED" | "CLOSED_OWNER_BLOCKS_CAPACITY" |
    "CLOSED_OWNER_RECONCILING" | "CAPACITY_RELEASED" |
    "OWNERSHIP_REVIEW_REQUIRED" | "CLOSED";
  title: string;
  blocker?: string | null;
  primaryAction: "NONE" | "WAIT" | "RECONCILE_REMOTE_CLOSE" |
    "RETRY_AGENT_RUN" | "CONTACT_PLATFORM_ADMINISTRATOR";
  primaryActionLabel?: string | null;
  primaryActionAvailable: boolean;
  requiredRole?: "ROUTINE_OPERATOR" | "PRIVILEGED_OPERATOR" |
    "PLATFORM_ADMINISTRATOR" | null;
  targetWorkSessionId?: number | null;
  targetAgentRunId?: number | null;
}

export interface LegacyRemoteClosePlan {
  planId: string;
  workSessionId: number;
  operation: "RECONCILE_REMOTE_CLOSE";
  state: "READY_FOR_CONFIRMATION" | "CONSUMED" | "EXPIRED";
  requiredRole: "PLATFORM_ADMINISTRATOR";
  ownershipFingerprintSha256: string;
  expiresAt: string;
  consumed: boolean;
  expectedImpact: string;
  valuesExposed: false;
  createdAt: string;
}

export interface LegacyRemoteCloseOperation {
  operationId: string;
  planId: string;
  workSessionId: number;
  operation: "RECONCILE_REMOTE_CLOSE";
  state: "REQUESTED" | "RECONCILING" | "BLOCKED" | "RELEASED";
  revision: number;
  ownershipFingerprintSha256: string;
  errorCode?: string | null;
  errorCategory?: string | null;
  nextAction: string;
  retryable: boolean;
  receiptSha256?: string | null;
  requestedAt: string;
  updatedAt: string;
  releasedAt?: string | null;
  valuesExposed: false;
}

export interface MobileSessionActions {
  canCreateTurn: boolean;
  canPublish: boolean;
  canSyncPullRequest: boolean;
  canClose: boolean;
  canGenerateDeliverables: boolean;
  canApproveDeliverables: boolean;
  canMarkApprovedPriceEstimateBilled: boolean;
}

export interface MobileSessionInsights {
  latestProgress?: string | null;
  currentBlocker?: { category?: string | null; summary?: string | null } | null;
  nextStepRecommended?: string | null;
}

export interface SessionDeliverablesView {
  sessionId: number;
  deliverables: SessionDeliverableSummary[];
  allCoreDeliverablesPresent: boolean;
  allCoreDeliverablesApproved: boolean;
  lastGeneratedAt?: string | null;
}

export interface SessionDeliverableSummary {
  id: number;
  type: string;
  status: string;
  version: number;
  title: string;
  approved: boolean;
  approvedAt?: string | null;
  updatedAt?: string | null;
  preview?: string | null;
  latestApprovedDeliverableId?: number | null;
}

export interface SessionDeliverable extends SessionDeliverableSummary {
  sessionId: number;
  contentMarkdown?: string | null;
  contentJson?: string | null;
  generationNotes?: string | null;
  errorMessage?: string | null;
  billingStatus?: string | null;
  billingReference?: string | null;
  billedAt?: string | null;
  createdAt?: string | null;
}

export interface ApprovedPriceEstimateSummary {
  sessionId: number;
  deliverableId: number;
  version: number;
  title: string;
  currency?: string | null;
  baseHourlyRate: number;
  equivalentHours: number;
  minimumPrice: number;
  recommendedPrice: number;
  maximumPrice: number;
  commercialPositioning?: string | null;
  riskLevel?: string | null;
  confidence?: string | null;
  assumptions: string[];
  exclusions: string[];
  billingStatus?: string | null;
  billingReference?: string | null;
  billedAt?: string | null;
  approvedAt?: string | null;
  updatedAt?: string | null;
}

export interface MobileSessionEvents {
  sessionId: number;
  events: MobileSessionEvent[];
  generatedAt?: string | null;
}

export interface MobileSessionEvent {
  type: string;
  at?: string | null;
  title: string;
  details?: string | null;
  runId?: number | null;
  turnId?: number | null;
  deliverableId?: number | null;
  eventId?: string | null;
  progressSequence?: number | null;
}

export interface ResolveMobileRescueSessionResult {
  created: boolean;
  view: MobileRescueConversation;
}

export interface MobileRescueConversation {
  session: MobileRescueSession;
  turns: MobileConversationTurn[];
}

export interface MobileRescueSession {
  id: number;
  projectId: number;
  projectName: string;
  repoPath?: string | null;
  status: string;
  title: string;
  canCreateTurn: boolean;
  lastActivityAt?: string | null;
}

export interface ManagedHost {
  id: number;
  name: string;
  description?: string | null;
  environment?: string | null;
  active: boolean;
}

export interface ManagedService {
  id: number;
  hostId: number;
  name: string;
  serviceType?: string | null;
  systemdUnit?: string | null;
  processPattern?: string | null;
  active: boolean;
}

export interface WebsiteCheck {
  websiteId: number;
  name: string;
  url: string;
  expectedStatus: number;
  statusCode?: number | null;
  durationMillis: number;
  degradedThresholdMillis: number;
  timeoutMillis: number;
  state: string;
  healthy: boolean;
  error?: string | null;
}

export interface OperationsActionRun {
  id: number;
  action: string;
  status: string;
  exitCode?: number | null;
  stdoutSummary?: string | null;
  stderrSummary?: string | null;
  report?: OperationsExecutionReport | null;
  startedAt?: string | null;
  finishedAt?: string | null;
}

export interface OperationsExecutionReport {
  action?: string | null;
  host?: string | null;
  status?: string | null;
  summary?: string | null;
  steps: OperationsExecutionStep[];
  metrics: Record<string, string>;
}

export interface OperationsExecutionStep {
  name?: string | null;
  status?: string | null;
  detail?: string | null;
}

export interface OperationsIncident {
  id: number;
  hostId?: number | null;
  hostName?: string | null;
  serviceId?: number | null;
  serviceName?: string | null;
  status: string;
  severity: string;
  title: string;
  summary?: string | null;
  openedAt?: string | null;
  lastActivityAt?: string | null;
  resolvedAt?: string | null;
}

export interface OperationsHostStatus {
  host: ManagedHost;
  hostStatusRun?: OperationsActionRun | null;
  services: ManagedService[];
  websiteChecks: WebsiteCheck[];
  openIncidents: OperationsIncident[];
}

export interface MobileUpload {
  originalFilename: string;
  storedFilename: string;
  contentType: string;
  sizeBytes: number;
  storedPath: string;
  latestMetadataPath: string;
  uploadedAt: string;
  telemetry?: {
    backendTotalMs: number;
    backendEnsureDirectoryMs: number;
    backendCopyMs: number;
    backendPermissionsMs: number;
    backendMetadataMs: number;
  } | null;
}

export interface MobileApiCostsOverview {
  generatedAt?: string | null;
  startAt?: string | null;
  endAt?: string | null;
  currency: string;
  total: number;
  providers: MobileApiCostProvider[];
  usageSummaries: MobileApiUsageSummary[];
  codexAuthStatuses: MobileCodexAuthStatus[];
}

export interface MobileApiCostProvider {
  provider: string;
  configured: boolean;
  status: string;
  currency: string;
  total: number;
  modelTotals: { provider: string; model: string; currency: string; amount: number }[];
  lines: { label: string; projectId?: string | null; model?: string | null; currency: string; amount: number }[];
}

export interface MobileApiUsageSummary {
  provider: string;
  usageType: string;
  status: string;
  requests: number;
  inputTokens: number;
  cachedInputTokens: number;
  outputTokens: number;
  inputAudioTokens: number;
  outputAudioTokens: number;
  characters: number;
  lines: unknown[];
}

export interface MobileCodexAuthStatus {
  server: string;
  configured: boolean;
  compliant: boolean;
  status: string;
  requiredAuthMode: string;
  authMode?: string | null;
  apiKeyPresent: boolean;
  tokensPresent: boolean;
}

export interface BillingQueueResponse {
  items?: BillingQueueItem[];
  summary?: BillingQueueSummary;
}

export interface BillingQueueItem {
  projectId?: number;
  projectName?: string;
  sessionId?: number;
  deliverableId?: number;
  title?: string;
  currency?: string;
  recommendedPrice?: number;
  billingStatus?: string;
  approvedAt?: string;
}

export interface BillingQueueSummary {
  totalItems?: number;
  totalReady?: number;
  totalBilled?: number;
  totalsByCurrency?: Record<string, number>;
}
