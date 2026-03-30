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
