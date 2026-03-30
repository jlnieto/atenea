import { API_BASE_URL } from './config';

type AuthAdapter = {
  getAccessToken: () => string | null;
  refreshAccessToken: () => Promise<string | null>;
  onAuthFailure: () => void;
};

type RequestOptions = {
  allowAuthRefresh?: boolean;
};

let authAdapter: AuthAdapter = {
  getAccessToken: () => null,
  refreshAccessToken: async () => null,
  onAuthFailure: () => undefined,
};

export function configureAuthAdapter(nextAdapter: Partial<AuthAdapter>) {
  authAdapter = {
    ...authAdapter,
    ...nextAdapter,
  };
}

async function performRequest(path: string, init?: RequestInit): Promise<Response> {
  const accessToken = authAdapter.getAccessToken();
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(init?.headers as Record<string, string> | undefined),
  };

  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }

  return fetch(`${API_BASE_URL}${path}`, {
    headers: {
      ...headers,
    },
    ...init,
  });
}

export async function fetchJson<T>(
  path: string,
  init?: RequestInit,
  options?: RequestOptions
): Promise<T> {
  let response = await performRequest(path, init);

  if (response.status === 401 && options?.allowAuthRefresh !== false) {
    const refreshedAccessToken = await authAdapter.refreshAccessToken();
    if (refreshedAccessToken) {
      response = await performRequest(path, init);
    } else {
      authAdapter.onAuthFailure();
    }
  }

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `HTTP ${response.status}`);
  }

  return response.json() as Promise<T>;
}

export function postJson<TResponse, TBody = unknown>(
  path: string,
  body?: TBody,
  options?: RequestOptions
): Promise<TResponse> {
  return fetchJson<TResponse>(path, {
    method: 'POST',
    body: body == null ? undefined : JSON.stringify(body),
  }, options);
}

export async function postEmpty<TBody = unknown>(
  path: string,
  body?: TBody,
  options?: RequestOptions
): Promise<void> {
  let response = await performRequest(path, {
    method: 'POST',
    body: body == null ? undefined : JSON.stringify(body),
  });

  if (response.status === 401 && options?.allowAuthRefresh !== false) {
    const refreshedAccessToken = await authAdapter.refreshAccessToken();
    if (refreshedAccessToken) {
      response = await performRequest(path, {
        method: 'POST',
        body: body == null ? undefined : JSON.stringify(body),
      });
    } else {
      authAdapter.onAuthFailure();
    }
  }

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `HTTP ${response.status}`);
  }
}
