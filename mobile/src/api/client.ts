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

type ApiErrorBody = {
  message?: string | null;
  details?: string[] | null;
  state?: string | null;
  reason?: string | null;
  action?: string | null;
  retryable?: boolean | null;
};

export class AteneaApiError extends Error {
  readonly status: number;
  readonly title: string;
  readonly detail: string | null;
  readonly action: string | null;
  readonly technicalMessage: string | null;

  constructor(params: {
    status: number;
    title: string;
    message: string;
    detail?: string | null;
    action?: string | null;
    technicalMessage?: string | null;
  }) {
    super(params.message);
    this.name = 'AteneaApiError';
    this.status = params.status;
    this.title = params.title;
    this.detail = params.detail ?? null;
    this.action = params.action ?? null;
    this.technicalMessage = params.technicalMessage ?? null;
  }
}

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
    throw await buildApiError(response);
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
    throw await buildApiError(response);
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
    throw await buildApiError(response);
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
    throw buildApiErrorFromText(response.status, response.body);
  }

  return JSON.parse(response.body) as TResponse;
}

async function buildApiError(response: Response): Promise<AteneaApiError> {
  return buildApiErrorFromText(response.status, await response.text());
}

function buildApiErrorFromText(status: number, text: string): AteneaApiError {
  const body = parseApiErrorBody(text);
  const message = normalizeBackendMessage(body?.message ?? text, status);
  const reason = normalizeBackendMessage(body?.reason ?? null, status);
  const action = normalizeBackendMessage(body?.action ?? null, status);
  const detail = action ?? reason ?? normalizeDetails(body?.details);
  return new AteneaApiError({
    status,
    title: titleForStatus(status),
    message,
    detail,
    action,
    technicalMessage: body?.message ?? (text || null),
  });
}

function parseApiErrorBody(text: string): ApiErrorBody | null {
  if (!text.trim()) {
    return null;
  }
  try {
    const parsed = JSON.parse(text) as unknown;
    if (parsed && typeof parsed === 'object') {
      return parsed as ApiErrorBody;
    }
  } catch {
    return null;
  }
  return null;
}

function normalizeDetails(details?: string[] | null) {
  if (!details?.length) {
    return null;
  }
  return details.map((detail) => humanizeBackendText(detail)).join('\n');
}

function normalizeBackendMessage(value: string | null, status: number) {
  const normalized = value == null ? '' : humanizeBackendText(value);
  if (normalized) {
    return normalized;
  }
  if (status === 401) {
    return 'Tu sesión no está activa. Vuelve a iniciar sesión.';
  }
  if (status === 403) {
    return 'No tienes permiso para realizar esta operación.';
  }
  if (status === 404) {
    return 'No he encontrado el recurso solicitado.';
  }
  if (status === 409) {
    return 'La operación no puede continuar porque el estado actual no lo permite.';
  }
  if (status === 422) {
    return 'Atenea no puede preparar esta operación con el estado actual del repositorio.';
  }
  if (status >= 500) {
    return 'Atenea no ha podido completar la operación en el servidor.';
  }
  return `La operación ha fallado. Código ${status}.`;
}

function titleForStatus(status: number) {
  if (status === 401) {
    return 'Sesión no autorizada';
  }
  if (status === 403) {
    return 'Acceso restringido';
  }
  if (status === 404) {
    return 'No encontrado';
  }
  if (status === 409) {
    return 'Estado incompatible';
  }
  if (status === 422) {
    return 'Operación bloqueada';
  }
  if (status >= 500) {
    return 'Error del servidor';
  }
  return 'No se pudo completar';
}

function humanizeBackendText(value: string) {
  let text = value.trim();
  if (!text) {
    return '';
  }

  text = text
    .replace(/Project repository is not operational for WorkSession branch preparation:\s*/i, '')
    .replace(/Repository '([^']+)' is not clean; cannot prepare WorkSession '([^']+)'/i,
      'El repositorio tiene cambios pendientes. Limpia, guarda o revisa esos cambios antes de abrir esta sesión.')
    .replace(/Repository is on branch '([^']+)' but WorkSession '([^']+)' can only prepare workspace branch '([^']+)' from base branch '([^']+)' or from the workspace branch itself\. Switch branches manually and retry\./i,
      'El repositorio está en una rama distinta de la rama base esperada. Cambia a la rama base o revisa la sesión anterior antes de continuar.')
    .replace(/Full authentication is required to access this resource/i,
      'Tu sesión no está activa. Vuelve a iniciar sesión.')
    .replace(/Invalid operator credentials/i,
      'Operador o contraseña incorrectos.');

  return text;
}
