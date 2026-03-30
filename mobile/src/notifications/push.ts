import Constants from 'expo-constants';
import * as Device from 'expo-device';
import * as Notifications from 'expo-notifications';
import { Platform } from 'react-native';
import { postEmpty, postJson } from '../api/client';
import { AteneaPushPayload, NotificationRouteTarget } from './types';

type RegisterPushTokenRequest = {
  expoPushToken: string;
  deviceId?: string;
  deviceName?: string;
  platform: string;
  appVersion?: string;
};

type UnregisterPushTokenRequest = {
  expoPushToken: string;
};

export const isRunningInExpoGo = Constants.appOwnership === 'expo';

if (!isRunningInExpoGo) {
  Notifications.setNotificationHandler({
    handleNotification: async () => ({
      shouldPlaySound: false,
      shouldSetBadge: false,
      shouldShowBanner: true,
      shouldShowList: true,
    }),
  });
}

export async function registerExpoPushToken(): Promise<string | null> {
  if (isRunningInExpoGo || !Device.isDevice) {
    return null;
  }

  const existingPermissions = await Notifications.getPermissionsAsync();
  let finalStatus = existingPermissions.status;
  if (finalStatus !== 'granted') {
    const requested = await Notifications.requestPermissionsAsync();
    finalStatus = requested.status;
  }
  if (finalStatus !== 'granted') {
    return null;
  }

  if (Platform.OS === 'android') {
    await Notifications.setNotificationChannelAsync('default', {
      name: 'default',
      importance: Notifications.AndroidImportance.DEFAULT,
    });
  }

  const projectId =
    process.env.EXPO_PUBLIC_EXPO_PROJECT_ID?.trim() ||
    Constants.expoConfig?.extra?.eas?.projectId ||
    Constants.easConfig?.projectId;

  const token = await Notifications.getExpoPushTokenAsync(
    projectId ? { projectId } : undefined
  );
  return token.data;
}

export async function syncPushToken(expoPushToken: string) {
  const payload: RegisterPushTokenRequest = {
    expoPushToken,
    deviceId: Device.osInternalBuildId ?? undefined,
    deviceName: Device.deviceName ?? Device.modelName ?? undefined,
    platform: Platform.OS,
    appVersion: Constants.expoConfig?.version,
  };

  await postJson('/api/mobile/notifications/push-token', payload);
}

export async function unregisterPushToken(expoPushToken: string) {
  await postEmpty<UnregisterPushTokenRequest>('/api/mobile/notifications/push-token/unregister', {
    expoPushToken,
  });
}

export function parseAteneaPushPayload(data: unknown): AteneaPushPayload | null {
  if (data == null || typeof data !== 'object' || Array.isArray(data)) {
    return null;
  }

  const candidate = data as Record<string, unknown>;
  const type = typeof candidate.type === 'string' ? candidate.type : null;
  if (
    type !== 'RUN_SUCCEEDED' &&
    type !== 'CLOSE_BLOCKED' &&
    type !== 'PULL_REQUEST_MERGED' &&
    type !== 'BILLING_READY'
  ) {
    return null;
  }

  return {
    type,
    sessionId: toOptionalNumber(candidate.sessionId),
    runId: toOptionalNumber(candidate.runId),
    deliverableId: toOptionalNumber(candidate.deliverableId),
    state: typeof candidate.state === 'string' ? candidate.state : undefined,
  };
}

export function resolveNotificationRoute(payload: AteneaPushPayload | null): NotificationRouteTarget | null {
  if (payload == null) {
    return null;
  }
  if (payload.type === 'BILLING_READY') {
    return {
      tab: 'billing',
      sessionId: payload.sessionId,
    };
  }
  if (payload.sessionId != null) {
    return {
      tab: 'session',
      sessionId: payload.sessionId,
    };
  }
  return {
    tab: 'inbox',
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
