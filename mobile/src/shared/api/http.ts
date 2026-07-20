import { ApiErrorFactory } from './errors';
import {
  getAuthController,
  isAuthEndpoint,
  readCsrfCookie,
  type AuthorizationHeader,
} from './auth';

const RETRY_HEADER = 'X-AF-Retry';

export interface ApiRequestOptions {
  /**
   * When true, the CSRF cookie is read and sent as X-CSRF-Token. Only
   * enable for state-changing auth endpoints (refresh, logout, login).
   */
  csrf?: boolean;
  /**
   * When true, suppress the automatic 401→refresh flow. Used internally by
   * the refresh call itself to avoid recursion.
   */
  skipRefresh?: boolean;
  /**
   * Optional AbortSignal forwarded to fetch.
   */
  signal?: AbortSignal;
}

function buildHeaders(
  init: RequestInit,
  auth: AuthorizationHeader,
  csrf: boolean,
): Headers {
  const headers = new Headers(init.headers);
  if (!headers.has('Accept')) {
    headers.set('Accept', 'application/json');
  }
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  if (auth.Authorization && !headers.has('Authorization')) {
    headers.set('Authorization', auth.Authorization);
  }
  if (csrf) {
    const token = readCsrfCookie();
    if (token && !headers.has('X-CSRF-Token')) {
      headers.set('X-CSRF-Token', token);
    }
  }
  return headers;
}

async function parseBody<T>(response: Response): Promise<T> {
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  if (text.length === 0) {
    return undefined as T;
  }
  return JSON.parse(text) as T;
}

export async function apiRequest<T>(path: string, init: RequestInit = {}, options: ApiRequestOptions = {}): Promise<T> {
  const controller = getAuthController();
  const authHeader = controller.authorizationHeader();
  const headers = buildHeaders(init, authHeader, Boolean(options.csrf));
  const isAuthPath = isAuthEndpoint(path);
  const headersRecord: Record<string, string> = {};
  headers.forEach((value, key) => {
    headersRecord[key] = value;
  });

  const response = await fetch(path, {
    ...init,
    headers: headersRecord,
    credentials: 'include',
    signal: options.signal ?? init.signal ?? null,
  });

  if (
    response.status === 401 &&
    !options.skipRefresh &&
    !isAuthPath &&
    !headers.has(RETRY_HEADER)
  ) {
    await controller.refresh();
    return apiRequest<T>(
      path,
      { ...init, headers: { ...headersRecord, [RETRY_HEADER]: '1' } },
      { ...options },
    );
  }

  if (!response.ok) {
    throw await ApiErrorFactory.fromResponse(response);
  }

  return parseBody<T>(response);
}
