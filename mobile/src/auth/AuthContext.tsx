import { ReactNode, createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import * as SecureStore from 'expo-secure-store';
import { configureAuthAdapter, postEmpty, postJson } from '../api/client';
import { registerExpoPushToken, syncPushToken, unregisterPushToken } from '../notifications/push';
import {
  MobileAuthSessionResponse,
  MobileLoginRequest,
  MobileLogoutRequest,
  MobileRefreshTokenRequest,
} from '../api/types';

type AuthContextValue = {
  session: MobileAuthSessionResponse | null;
  loading: boolean;
  login: (request: MobileLoginRequest) => Promise<void>;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);
const SESSION_STORAGE_KEY = 'atenea.mobile.auth.session';

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<MobileAuthSessionResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [hydrating, setHydrating] = useState(true);
  const refreshPromiseRef = useRef<Promise<string | null> | null>(null);
  const pushTokenRef = useRef<string | null>(null);

  const clearSession = useCallback(() => {
    setSession(null);
  }, []);

  useEffect(() => {
    let cancelled = false;

    const hydrate = async () => {
      try {
        const available = await SecureStore.isAvailableAsync();
        if (!available) {
          return;
        }
        const persisted = await SecureStore.getItemAsync(SESSION_STORAGE_KEY);
        if (!persisted || cancelled) {
          return;
        }
        setSession(JSON.parse(persisted) as MobileAuthSessionResponse);
      } finally {
        if (!cancelled) {
          setHydrating(false);
        }
      }
    };

    void hydrate();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (hydrating) {
      return;
    }

    const persist = async () => {
      const available = await SecureStore.isAvailableAsync();
      if (!available) {
        return;
      }
      if (session == null) {
        await SecureStore.deleteItemAsync(SESSION_STORAGE_KEY);
        return;
      }
      await SecureStore.setItemAsync(SESSION_STORAGE_KEY, JSON.stringify(session));
    };

    void persist();
  }, [session, hydrating]);

  const refreshAccessToken = useCallback(async () => {
    if (!session?.refreshToken) {
      return null;
    }
    if (refreshPromiseRef.current) {
      return refreshPromiseRef.current;
    }

    const refreshPromise = (async () => {
      try {
        const nextSession = await postJson<MobileAuthSessionResponse, MobileRefreshTokenRequest>(
          '/api/mobile/auth/refresh',
          { refreshToken: session.refreshToken },
          { allowAuthRefresh: false }
        );
        setSession(nextSession);
        return nextSession.accessToken;
      } catch (error) {
        setSession(null);
        return null;
      } finally {
        refreshPromiseRef.current = null;
      }
    })();

    refreshPromiseRef.current = refreshPromise;
    return refreshPromise;
  }, [session]);

  useEffect(() => {
    configureAuthAdapter({
      getAccessToken: () => session?.accessToken ?? null,
      refreshAccessToken,
      onAuthFailure: clearSession,
    });
  }, [session, refreshAccessToken, clearSession]);

  const login = useCallback(async (request: MobileLoginRequest) => {
    setLoading(true);
    try {
      const nextSession = await postJson<MobileAuthSessionResponse, MobileLoginRequest>(
        '/api/mobile/auth/login',
        request,
        { allowAuthRefresh: false }
      );
      setSession(nextSession);
    } finally {
      setLoading(false);
    }
  }, []);

  const logout = useCallback(async () => {
    const currentPushToken = pushTokenRef.current;
    if (!session?.refreshToken) {
      setSession(null);
      return;
    }
    try {
      if (currentPushToken) {
        await unregisterPushToken(currentPushToken);
      }
      await postEmpty<MobileLogoutRequest>(
        '/api/mobile/auth/logout',
        { refreshToken: session.refreshToken },
        { allowAuthRefresh: false }
      );
    } finally {
      pushTokenRef.current = null;
      setSession(null);
    }
  }, [session]);

  useEffect(() => {
    if (hydrating || session == null) {
      return undefined;
    }

    let cancelled = false;
    const sync = async () => {
      try {
        const expoPushToken = await registerExpoPushToken();
        if (!expoPushToken || cancelled) {
          return;
        }
        await syncPushToken(expoPushToken);
        if (!cancelled) {
          pushTokenRef.current = expoPushToken;
        }
      } catch (error) {
        if (!cancelled) {
          pushTokenRef.current = null;
        }
      }
    };

    void sync();
    return () => {
      cancelled = true;
    };
  }, [session, hydrating]);

  const value = useMemo<AuthContextValue>(() => ({
    session,
    loading: loading || hydrating,
    login,
    logout,
  }), [session, loading, hydrating, login, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (context == null) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return context;
}
