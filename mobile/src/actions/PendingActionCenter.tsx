import * as SecureStore from 'expo-secure-store';
import {
  ReactNode,
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { useAuth } from '../auth/AuthContext';

export type MobilePendingAction = {
  label: string;
  scope: 'session' | 'project';
  startedAt: string;
  recoveryHint: string;
  sessionId?: number;
  projectId?: number;
};

type PendingActionCenterValue = {
  pendingAction: MobilePendingAction | null;
  startPendingAction: (action: MobilePendingAction) => void;
  clearPendingAction: () => void;
};

const PendingActionCenterContext = createContext<PendingActionCenterValue | null>(null);
const PENDING_ACTION_STORAGE_KEY_PREFIX = 'atenea.mobile.pending-action.operator';

export function PendingActionCenterProvider({ children }: { children: ReactNode }) {
  const { session } = useAuth();
  const [pendingAction, setPendingAction] = useState<MobilePendingAction | null>(null);
  const storageKey = session != null
    ? `${PENDING_ACTION_STORAGE_KEY_PREFIX}.${session.operator.id}`
    : null;

  useEffect(() => {
    let cancelled = false;

    const hydrate = async () => {
      if (storageKey == null) {
        setPendingAction(null);
        return;
      }

      const available = await SecureStore.isAvailableAsync();
      if (!available || cancelled) {
        return;
      }

      const persisted = await SecureStore.getItemAsync(storageKey);
      if (!persisted || cancelled) {
        setPendingAction(null);
        return;
      }

      try {
        const parsed = JSON.parse(persisted) as MobilePendingAction;
        if (!cancelled) {
          setPendingAction(parsed);
        }
      } catch {
        if (!cancelled) {
          setPendingAction(null);
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

      if (pendingAction == null) {
        await SecureStore.deleteItemAsync(storageKey);
        return;
      }

      await SecureStore.setItemAsync(storageKey, JSON.stringify(pendingAction));
    };

    void persist();
    return () => {
      cancelled = true;
    };
  }, [pendingAction, storageKey]);

  const startPendingAction = useCallback((action: MobilePendingAction) => {
    setPendingAction(action);
  }, []);

  const clearPendingAction = useCallback(() => {
    setPendingAction(null);
  }, []);

  const value = useMemo<PendingActionCenterValue>(() => ({
    pendingAction,
    startPendingAction,
    clearPendingAction,
  }), [pendingAction, startPendingAction, clearPendingAction]);

  return (
    <PendingActionCenterContext.Provider value={value}>
      {children}
    </PendingActionCenterContext.Provider>
  );
}

export function usePendingActionCenter() {
  const context = useContext(PendingActionCenterContext);
  if (context == null) {
    throw new Error('usePendingActionCenter must be used inside PendingActionCenterProvider');
  }
  return context;
}
