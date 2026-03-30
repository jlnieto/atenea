import * as Notifications from 'expo-notifications';
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
import { isRunningInExpoGo, parseAteneaPushPayload, resolveNotificationRoute } from './push';
import { MobileNotificationItem, NotificationRouteTarget } from './types';

type NotificationCenterValue = {
  notifications: MobileNotificationItem[];
  consumePendingRoute: () => NotificationRouteTarget | null;
  routeToNotification: (notification: MobileNotificationItem) => NotificationRouteTarget | null;
  dismissNotification: (id: string) => void;
  clearNotifications: () => void;
};

const NotificationCenterContext = createContext<NotificationCenterValue | null>(null);

export function NotificationCenterProvider({ children }: { children: ReactNode }) {
  const [notifications, setNotifications] = useState<MobileNotificationItem[]>([]);
  const pendingRouteRef = useRef<NotificationRouteTarget | null>(null);
  const hydratedInitialResponseRef = useRef(false);

  const appendNotification = useCallback((notification: Notifications.Notification) => {
    const payload = parseAteneaPushPayload(notification.request.content.data);
    const route = resolveNotificationRoute(payload);
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
      return next.slice(0, 12);
    });
    return route;
  }, []);

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
    setNotifications([]);
  }, []);

  const value = useMemo<NotificationCenterValue>(
    () => ({
      notifications,
      consumePendingRoute,
      routeToNotification,
      dismissNotification,
      clearNotifications,
    }),
    [notifications, consumePendingRoute, routeToNotification, dismissNotification, clearNotifications]
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
