import { API_BASE_URL } from './config';
import * as FileSystem from 'expo-file-system/legacy';

type AuthAdapter = {
  getAccessToken: () => string | null;
  refreshAccessToken: () => Promise<string | null>;
  onAuthFailure: () => void;
};

type RequestOptions = {
  allowAuthRefresh?: boolean;
};

type MultipartValue =
  | string
  | number
  | {
    uri: string;
    name: string;
    type: string;
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
  const requestHeaders = (init?.headers as Record<string, string> | undefined) ?? {};
  const isMultipart = typeof FormData !== 'undefined' && init?.body instanceof FormData;
  const headers: Record<string, string> = {
    ...(!isMultipart ? { 'Content-Type': 'application/json' } : {}),
    ...requestHeaders,
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

export async function postMultipart<TResponse>(
  path: string,
  fields: Record<string, MultipartValue | null | undefined>,
  options?: RequestOptions
): Promise<TResponse> {
  const formData = new FormData();
  Object.entries(fields).forEach(([key, value]) => {
    if (value == null) {
      return;
    }
    if (typeof value === 'string' || typeof value === 'number') {
      formData.append(key, String(value));
      return;
    }
    formData.append(key, value as never);
  });

  let response = await performRequest(path, {
    method: 'POST',
    body: formData,
  });

  if (response.status === 401 && options?.allowAuthRefresh !== false) {
    const refreshedAccessToken = await authAdapter.refreshAccessToken();
    if (refreshedAccessToken) {
      response = await performRequest(path, {
        method: 'POST',
        body: formData,
      });
    } else {
      authAdapter.onAuthFailure();
    }
  }

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `HTTP ${response.status}`);
  }

  return response.json() as Promise<TResponse>;
}

export async function uploadMultipartFile<TResponse>(
  path: string,
  file: {
    uri: string;
    name?: string;
    type?: string;
    fieldName?: string;
  },
  parameters?: Record<string, string | number | null | undefined>,
  options?: RequestOptions
): Promise<TResponse> {
  const resolvedFieldName = file.fieldName ?? 'file';
  const resolvedFileName = file.name ?? 'upload.bin';
  const resolvedMimeType = file.type ?? 'application/octet-stream';

  const fileInfo = await FileSystem.getInfoAsync(file.uri);
  if (!fileInfo.exists) {
    throw new Error(`Upload file does not exist at ${file.uri}`);
  }

  const upload = async () => {
    const accessToken = authAdapter.getAccessToken();
    return FileSystem.uploadAsync(`${API_BASE_URL}${path}`, file.uri, {
      fieldName: resolvedFieldName,
      headers: accessToken
        ? { Authorization: `Bearer ${accessToken}` }
        : undefined,
      httpMethod: 'POST',
      mimeType: resolvedMimeType,
      parameters: Object.fromEntries(
        Object.entries(parameters ?? {})
          .filter(([, value]) => value != null)
          .map(([key, value]) => [key, String(value)])
      ),
      uploadType: FileSystem.FileSystemUploadType.MULTIPART,
    });
  };

  let response = await upload();

  if (response.status === 401 && options?.allowAuthRefresh !== false) {
    const refreshedAccessToken = await authAdapter.refreshAccessToken();
    if (refreshedAccessToken) {
      response = await upload();
    } else {
      authAdapter.onAuthFailure();
    }
  }

  if (response.status < 200 || response.status >= 300) {
    throw new Error(response.body || `HTTP ${response.status}`);
  }

  return JSON.parse(response.body) as TResponse;
}
