export type AteneaPushEventType =
  | 'RUN_SUCCEEDED'
  | 'CLOSE_BLOCKED'
  | 'PULL_REQUEST_MERGED'
  | 'BILLING_READY';

export type AteneaPushPayload = {
  type: AteneaPushEventType;
  sessionId?: number;
  runId?: number;
  deliverableId?: number;
  state?: string;
};

export type NotificationRouteTarget = {
  tab: 'inbox' | 'projects' | 'session' | 'billing';
  sessionId?: number;
};

export type MobileNotificationItem = {
  id: string;
  title: string;
  body: string | null;
  receivedAt: string;
  payload: AteneaPushPayload | null;
  route: NotificationRouteTarget | null;
};
