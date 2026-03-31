export type MobileInboxResponse = {
  items: MobileInboxItem[];
  summary: {
    runInProgressCount: number;
    closeBlockedCount: number;
    pullRequestOpenCount: number;
    readyToCloseCount: number;
    billingReadyCount: number;
  };
};

export type OperatorProfile = {
  id: number;
  email: string;
  displayName: string;
};

export type MobileLoginRequest = {
  email: string;
  password: string;
};

export type MobileRefreshTokenRequest = {
  refreshToken: string;
};

export type MobileLogoutRequest = {
  refreshToken: string;
};

export type MobileAuthSessionResponse = {
  accessToken: string;
  accessTokenExpiresAt: string;
  refreshToken: string;
  refreshTokenExpiresAt: string;
  operator: OperatorProfile;
};

export type MobilePushDeviceResponse = {
  id: number;
  expoPushToken: string;
  deviceId: string | null;
  deviceName: string | null;
  platform: string;
  appVersion: string | null;
  active: boolean;
  lastRegisteredAt: string;
  updatedAt: string;
};

export type MobileInboxItem = {
  type: string;
  severity: string;
  title: string;
  message: string | null;
  action: string | null;
  projectId: number | null;
  projectName: string | null;
  sessionId: number | null;
  sessionTitle: string | null;
  updatedAt: string | null;
};

export type MobileProjectOverview = {
  projectId: number;
  projectName: string;
  description: string | null;
  defaultBaseBranch: string | null;
  session: {
    sessionId: number;
    status: string;
    title: string;
    runInProgress: boolean;
    closeBlockedState: string | null;
    pullRequestStatus: string | null;
    lastActivityAt: string | null;
  } | null;
};

export type ResolveSessionRequest = {
  title?: string;
  baseBranch?: string;
};

export type ResolveSessionResponse = {
  created: boolean;
  view: WorkSessionConversationView;
};

export type MobileSessionSummary = {
  conversation: WorkSessionConversationView;
  approvedDeliverables: {
    deliverables: Array<{
      id: number;
      type: string;
      title: string | null;
      approved: boolean;
      preview: string | null;
    }>;
  };
  approvedPriceEstimate: {
    deliverableId: number;
    recommendedPrice: number;
    currency: string;
    billingStatus: string;
    billingReference: string | null;
  } | null;
  actions: {
    canCreateTurn: boolean;
    canPublish: boolean;
    canSyncPullRequest: boolean;
    canClose: boolean;
    canGenerateDeliverables: boolean;
    canApproveDeliverables: boolean;
    canMarkApprovedPriceEstimateBilled: boolean;
  };
};

export type CreateSessionTurnRequest = {
  message: string;
};

export type WorkSessionConversationView = {
  view: {
    session: {
      id: number;
      title: string;
      status: string;
      pullRequestStatus: string | null;
      closeBlockedState: string | null;
      closeBlockedReason: string | null;
      closeBlockedAction: string | null;
      closeRetryable: boolean;
    };
    runInProgress: boolean;
    canCreateTurn: boolean;
    lastError: string | null;
    lastAgentResponse: string | null;
  };
  recentTurnLimit?: number;
  historyTruncated?: boolean;
  recentTurns: Array<{
    id: number;
    actor: string;
    messageText: string;
    createdAt: string;
  }>;
};

export type CreateSessionTurnConversationViewResponse = {
  view: WorkSessionConversationView;
};

export type SessionDeliverablesView = {
  sessionId: number;
  allCoreDeliverablesPresent: boolean;
  allCoreDeliverablesApproved: boolean;
  lastGeneratedAt: string | null;
  deliverables: Array<{
    id: number;
    type: string;
    status: string;
    version: number;
    title: string | null;
    approved: boolean;
    approvedAt: string | null;
    updatedAt: string | null;
    preview: string | null;
    latestApprovedDeliverableId: number | null;
  }>;
};

export type MarkPriceEstimateBilledRequest = {
  billingReference: string;
};

export type BillingQueueResponse = {
  items: Array<{
    projectId: number;
    projectName: string;
    sessionId: number;
    sessionTitle: string;
    deliverableId: number;
    billingStatus: string;
    billingReference: string | null;
    recommendedPrice: number;
    currency: string;
    approvedAt: string | null;
    pullRequestStatus: string | null;
  }>;
};

export type MobileSessionEventsResponse = {
  sessionId: number;
  generatedAt: string;
  events: Array<{
    type: string;
    at: string;
    title: string;
    details: string | null;
    runId: number | null;
    turnId: number | null;
    deliverableId: number | null;
  }>;
};

export type CoreChannel = 'TEXT' | 'VOICE';

export type CoreCommandStatus =
  | 'RECEIVED'
  | 'NEEDS_CLARIFICATION'
  | 'SUCCEEDED'
  | 'NEEDS_CONFIRMATION'
  | 'REJECTED'
  | 'FAILED';

export type CoreInterpreterSource = 'DETERMINISTIC' | 'LLM' | 'DETERMINISTIC_FALLBACK';

export type CoreDomain = 'DEVELOPMENT' | 'OPERATIONS' | 'COMMUNICATIONS';

export type CoreRiskLevel = 'READ' | 'SAFE_WRITE' | 'DESTRUCTIVE';

export type CoreResultType =
  | 'PROJECT_OVERVIEW_LIST'
  | 'PROJECT_OVERVIEW'
  | 'PROJECT_CONTEXT'
  | 'WORK_SESSION_SUMMARY'
  | 'SESSION_DELIVERABLES_VIEW'
  | 'SESSION_DELIVERABLE'
  | 'WORK_SESSION'
  | 'WORK_SESSION_VIEW'
  | 'WORK_SESSION_CONVERSATION_VIEW';

export type CoreTargetType =
  | 'PROJECT'
  | 'WORK_SESSION'
  | 'SESSION_TURN'
  | 'SESSION_DELIVERABLE'
  | 'OPERATOR_CONTEXT';

export type CoreCommandEventPhase =
  | 'RECEIVED'
  | 'INTERPRETING'
  | 'RESOLVING_CONTEXT'
  | 'NEEDS_CLARIFICATION'
  | 'NEEDS_CONFIRMATION'
  | 'EXECUTING'
  | 'SUCCEEDED'
  | 'REJECTED'
  | 'FAILED';

export type CoreRequestContext = {
  projectId?: number | null;
  workSessionId?: number | null;
  operatorKey?: string | null;
};

export type CoreConfirmationRequest = {
  confirmed: boolean;
  confirmationToken?: string | null;
};

export type CreateCoreCommandRequest = {
  input: string;
  channel: CoreChannel;
  context?: CoreRequestContext | null;
  confirmation?: CoreConfirmationRequest | null;
};

export type ConfirmCoreCommandRequest = {
  confirmationToken: string;
};

export type CoreInterpretationResponse = {
  source: CoreInterpreterSource;
  detail: string;
};

export type CoreIntentResponse = {
  intent: string;
  domain: CoreDomain;
  capability: string;
  riskLevel: CoreRiskLevel;
  requiresConfirmation: boolean;
  confidence: number;
};

export type CoreCommandResultResponse = {
  type: CoreResultType;
  targetType: CoreTargetType | null;
  targetId: number | null;
  payload: unknown;
};

export type CoreClarificationOptionResponse = {
  type: string;
  targetId: number | null;
  label: string;
};

export type CoreClarificationResponse = {
  message: string;
  options: CoreClarificationOptionResponse[];
};

export type CoreConfirmationResponse = {
  confirmationToken: string;
  message: string;
};

export type CoreCommandResponse = {
  commandId: number;
  status: CoreCommandStatus;
  interpretation: CoreInterpretationResponse | null;
  intent: CoreIntentResponse | null;
  result: CoreCommandResultResponse | null;
  clarification: CoreClarificationResponse | null;
  confirmation: CoreConfirmationResponse | null;
  operatorMessage: string | null;
  speakableMessage: string | null;
};

export type CoreCommandSummaryResponse = {
  commandId: number;
  status: CoreCommandStatus;
  interpretation: CoreInterpretationResponse | null;
  intent: CoreIntentResponse | null;
  rawInput: string;
  resultSummary: string | null;
  errorCode: string | null;
  errorMessage: string | null;
  operatorMessage: string | null;
  speakableMessage: string | null;
  createdAt: string;
  finishedAt: string | null;
};

export type CoreCommandListResponse = {
  items: CoreCommandSummaryResponse[];
};

export type CoreCommandEventResponse = {
  id: number;
  phase: CoreCommandEventPhase;
  message: string;
  payload: unknown;
  at: string;
};

export type CoreCommandEventsResponse = {
  commandId: number;
  events: CoreCommandEventResponse[];
  generatedAt: string;
};

export type CoreVoiceCommandResponse = {
  transcript: string;
  command: CoreCommandResponse;
};
