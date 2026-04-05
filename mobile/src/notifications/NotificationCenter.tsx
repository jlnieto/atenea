import * as Notifications from 'expo-notifications';
import * as SecureStore from 'expo-secure-store';
import {
  ReactNode,
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { useAuth } from '../auth/AuthContext';
import { isRunningInExpoGo, parseAteneaPushPayload, resolveNotificationRoute } from './push';
import { MobileNotificationItem, NotificationRouteTarget } from './types';

type NotificationCenterValue = {
  notifications: MobileNotificationItem[];
  consumePendingRoute: () => NotificationRouteTarget | null;
  routeToNotification: (notification: MobileNotificationItem) => NotificationRouteTarget | null;
  dismissNotification: (id: string) => void;
  clearNotifications: () => void;
  publishAppNotification: (notification: {
    id?: string;
    title: string;
    body?: string | null;
    route: NotificationRouteTarget | null;
  }) => Promise<void>;
};

const NotificationCenterContext = createContext<NotificationCenterValue | null>(null);
const NOTIFICATION_STORAGE_KEY_PREFIX = 'atenea.mobile.notifications.operator';
const MAX_NOTIFICATIONS = 12;

export function NotificationCenterProvider({ children }: { children: ReactNode }) {
  const { session } = useAuth();
  const [notifications, setNotifications] = useState<MobileNotificationItem[]>([]);
  const pendingRouteRef = useRef<NotificationRouteTarget | null>(null);
  const hydratedInitialResponseRef = useRef(false);
  const storageKey = session != null
    ? `${NOTIFICATION_STORAGE_KEY_PREFIX}.${session.operator.id}`
    : null;

  const appendNotification = useCallback((notification: Notifications.Notification) => {
    const payload = parseAteneaPushPayload(notification.request.content.data);
    const route = resolveNotificationRoute(payload) ?? parseRouteFromNotificationData(notification.request.content.data);
    const body =
      typeof notification.request.content.body === 'string' ? notification.request.content.body : null;
    const item: MobileNotificationItem = {
      id: notification.request.identifier,
      title: notification.request.content.title ?? 'Atenea',
      body,
      receivedAt: new Date().toISOString(),
      payload,
      route,
    };

    setNotifications((current) => {
      const next = [item, ...current.filter((entry) => entry.id !== item.id)];
      return next.slice(0, MAX_NOTIFICATIONS);
    });
    return route;
  }, []);

  const appendNotificationItem = useCallback((item: MobileNotificationItem) => {
    setNotifications((current) => {
      const next = [item, ...current.filter((entry) => entry.id !== item.id)];
      return next.slice(0, MAX_NOTIFICATIONS);
    });
  }, []);

  useEffect(() => {
    let cancelled = false;

    const hydrate = async () => {
      if (storageKey == null) {
        pendingRouteRef.current = null;
        setNotifications([]);
        return;
      }

      const available = await SecureStore.isAvailableAsync();
      if (!available || cancelled) {
        return;
      }
      const persisted = await SecureStore.getItemAsync(storageKey);
      if (!persisted || cancelled) {
        setNotifications([]);
        return;
      }

      try {
        const parsed = JSON.parse(persisted) as MobileNotificationItem[];
        if (!cancelled) {
          setNotifications(parsed.slice(0, MAX_NOTIFICATIONS));
        }
      } catch {
        if (!cancelled) {
          setNotifications([]);
        }
      }
    };

    void hydrate();
    return () => {
      cancelled = true;
    };
  }, [storageKey]);

  useEffect(() => {
    let cancelled = false;

    const persist = async () => {
      if (storageKey == null) {
        return;
      }

      const available = await SecureStore.isAvailableAsync();
      if (!available || cancelled) {
        return;
      }

      if (notifications.length === 0) {
        await SecureStore.deleteItemAsync(storageKey);
        return;
      }

      await SecureStore.setItemAsync(storageKey, JSON.stringify(notifications.slice(0, MAX_NOTIFICATIONS)));
    };

    void persist();
    return () => {
      cancelled = true;
    };
  }, [notifications, storageKey]);

  useEffect(() => {
    if (isRunningInExpoGo) {
      return undefined;
    }

    const receivedSubscription = Notifications.addNotificationReceivedListener((notification) => {
      appendNotification(notification);
    });

    const responseSubscription = Notifications.addNotificationResponseReceivedListener((response) => {
      const route = appendNotification(response.notification);
      pendingRouteRef.current = route;
    });

    const hydrateLastResponse = async () => {
      if (hydratedInitialResponseRef.current) {
        return;
      }
      hydratedInitialResponseRef.current = true;
      const response = await Notifications.getLastNotificationResponseAsync();
      if (response == null) {
        return;
      }
      const route = appendNotification(response.notification);
      pendingRouteRef.current = route;
      await Notifications.clearLastNotificationResponseAsync();
    };

    void hydrateLastResponse();

    return () => {
      receivedSubscription.remove();
      responseSubscription.remove();
    };
  }, [appendNotification]);

  const consumePendingRoute = useCallback(() => {
    const next = pendingRouteRef.current;
    pendingRouteRef.current = null;
    return next;
  }, []);

  const routeToNotification = useCallback((notification: MobileNotificationItem) => {
    pendingRouteRef.current = null;
    return notification.route;
  }, []);

  const dismissNotification = useCallback((id: string) => {
    setNotifications((current) => current.filter((item) => item.id !== id));
  }, []);

  const clearNotifications = useCallback(() => {
    pendingRouteRef.current = null;
    setNotifications([]);
  }, []);

  const publishAppNotification = useCallback(async (notification: {
    id?: string;
    title: string;
    body?: string | null;
    route: NotificationRouteTarget | null;
  }) => {
    const item: MobileNotificationItem = {
      id: notification.id ?? `app-${Date.now()}`,
      title: notification.title,
      body: notification.body ?? null,
      receivedAt: new Date().toISOString(),
      payload: null,
      route: notification.route,
    };
    appendNotificationItem(item);

    if (isRunningInExpoGo) {
      return;
    }

    try {
      await Notifications.scheduleNotificationAsync({
        content: {
          title: item.title,
          body: item.body ?? undefined,
          data: {
            route: item.route,
            appNotificationId: item.id,
          },
        },
        trigger: null,
      });
    } catch {
      // Keep the in-app notification even if system scheduling is unavailable.
    }
  }, [appendNotificationItem]);

  const value = useMemo<NotificationCenterValue>(
    () => ({
      notifications,
      consumePendingRoute,
      routeToNotification,
      dismissNotification,
      clearNotifications,
      publishAppNotification,
    }),
    [notifications, consumePendingRoute, routeToNotification, dismissNotification, clearNotifications, publishAppNotification]
  );

  return (
    <NotificationCenterContext.Provider value={value}>
      {children}
    </NotificationCenterContext.Provider>
  );
}

export function useNotificationCenter() {
  const context = useContext(NotificationCenterContext);
  if (context == null) {
    throw new Error('useNotificationCenter must be used inside NotificationCenterProvider');
  }
  return context;
}

function parseRouteFromNotificationData(data: unknown): NotificationRouteTarget | null {
  if (data == null || typeof data !== 'object' || Array.isArray(data)) {
    return null;
  }
  const candidate = data as Record<string, unknown>;
  const rawRoute = candidate.route;
  if (rawRoute == null || typeof rawRoute !== 'object' || Array.isArray(rawRoute)) {
    return null;
  }
  const route = rawRoute as Record<string, unknown>;
  const tab = typeof route.tab === 'string' ? route.tab : null;
  if (
    tab !== 'inbox'
    && tab !== 'projects'
    && tab !== 'session'
    && tab !== 'conversation'
    && tab !== 'billing'
  ) {
    return null;
  }
  return {
    tab,
    projectId: toOptionalNumber(route.projectId),
    sessionId: toOptionalNumber(route.sessionId),
    autoReadMode: route.autoReadMode === 'full' ? 'full' : route.autoReadMode === 'brief' ? 'brief' : undefined,
  };
}

function toOptionalNumber(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }
  return undefined;
}
