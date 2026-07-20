import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from './errors';
import { apiRequest } from './http';
import { setAuthController } from './auth';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function emptyResponse(status: number): Response {
  return new Response(null, { status });
}

const noop = async () => {
  /* noop */
};

function headersOf(mock: ReturnType<typeof vi.fn>, index: number): Headers {
  const callArgs = mock.mock.calls[index] as [string, RequestInit] | undefined;
  const init = callArgs?.[1] ?? {};
  return new Headers((init.headers ?? {}) as HeadersInit);
}

describe('apiRequest', () => {
  beforeEach(() => {
    document.cookie = 'antflow-csrf=test-csrf-token';
  });

  afterEach(() => {
    document.cookie = 'antflow-csrf=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('parses JSON success responses', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { ok: true }));
    vi.stubGlobal('fetch', fetchMock);

    setAuthController({
      authorizationHeader: () => ({ Authorization: 'Bearer t' }),
      refresh: noop,
      isAuthEndpoint: () => false,
    });

    const result = await apiRequest<{ ok: boolean }>('/api/example');
    expect(result).toEqual({ ok: true });
    const headers = headersOf(fetchMock, 0);
    expect(headers.get('Accept')).toBe('application/json');
    expect(headers.get('Authorization')).toBe('Bearer t');
    expect(headers.has('X-CSRF-Token')).toBe(false);
  });

  it('returns undefined for 204 responses', async () => {
    const fetchMock = vi.fn().mockResolvedValue(emptyResponse(204));
    vi.stubGlobal('fetch', fetchMock);
    setAuthController({
      authorizationHeader: () => ({}),
      refresh: noop,
      isAuthEndpoint: () => false,
    });

    const result = await apiRequest<void>('/api/example');
    expect(result).toBeUndefined();
  });

  it('throws ApiError for structured error responses', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(422, {
          code: 'INVALID_FORM',
          message: '字段缺失',
          fieldErrors: [{ field: 'amount', message: 'must be positive' }],
        }),
      ),
    );
    setAuthController({
      authorizationHeader: () => ({}),
      refresh: noop,
      isAuthEndpoint: () => false,
    });

    const error = await apiRequest('/api/example').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.body.code).toBe('INVALID_FORM');
    expect(apiError.body.fieldErrors).toEqual([
      { field: 'amount', message: 'must be positive' },
    ]);
  });

  it('refreshes once and retries on 401 for non-auth endpoints', async () => {
    const refresh = vi.fn(noop);
    let attempts = 0;
    const fetchMock = vi.fn().mockImplementation(async () => {
      attempts += 1;
      if (attempts === 1) return jsonResponse(401, { code: 'TOKEN_EXPIRED', message: 'expired' });
      return jsonResponse(200, { ok: true });
    });
    vi.stubGlobal('fetch', fetchMock);
    setAuthController({
      authorizationHeader: () => ({ Authorization: 'Bearer rotated' }),
      refresh,
      isAuthEndpoint: (p) => p.includes('/auth/'),
    });

    const result = await apiRequest<{ ok: boolean }>('/api/example');
    expect(result).toEqual({ ok: true });
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const retryHeaders = headersOf(fetchMock, 1);
    expect(retryHeaders.get('X-AF-Retry')).toBe('1');
  });

  it('does not retry POST that returns 401 again after refresh', async () => {
    const refresh = vi.fn(noop);
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, { code: 'TOKEN_EXPIRED', message: 'expired' }))
      .mockResolvedValueOnce(jsonResponse(401, { code: 'TOKEN_EXPIRED', message: 'expired' }));
    vi.stubGlobal('fetch', fetchMock);
    setAuthController({
      authorizationHeader: () => ({}),
      refresh,
      isAuthEndpoint: (p) => p.includes('/auth/'),
    });

    await expect(
      apiRequest('/api/example', { method: 'POST', body: JSON.stringify({}) }),
    ).rejects.toBeInstanceOf(ApiError);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('sends X-CSRF-Token only when csrf option is set on auth endpoints', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { ok: true }));
    vi.stubGlobal('fetch', fetchMock);
    setAuthController({
      authorizationHeader: () => ({}),
      refresh: noop,
      isAuthEndpoint: () => true,
    });

    await apiRequest('/api/auth/refresh', { method: 'POST' }, { csrf: true });
    const headers = headersOf(fetchMock, 0);
    expect(headers.get('X-CSRF-Token')).toBe('test-csrf-token');
  });

  it('does not copy arbitrary cookies onto business headers', async () => {
    document.cookie = 'tracking=1';
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { ok: true }));
    vi.stubGlobal('fetch', fetchMock);
    setAuthController({
      authorizationHeader: () => ({ Authorization: 'Bearer t' }),
      refresh: noop,
      isAuthEndpoint: () => false,
    });

    await apiRequest('/api/example');
    const headers = headersOf(fetchMock, 0);
    expect(headers.has('Cookie')).toBe(false);
    expect(headers.has('tracking')).toBe(false);
  });
});
