import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore, safeReturnUrl } from './auth.store';
import type { MobileUser } from '../../shared/api/types';

const SAMPLE_USER: MobileUser = {
  id: 7,
  username: 'admin',
  displayName: '管理员',
  roles: ['admin'],
};

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function emptyResponse(status: number): Response {
  return new Response(null, { status });
}

describe('safeReturnUrl', () => {
  it.each([
    ['/workbench', true],
    ['/tasks?status=pending', true],
    ['', false],
    ['//evil.com', false],
    ['http://evil.com', false],
    ['javascript:alert(1)', false],
    ['workbench', false],
    [null, false],
    [undefined, false],
  ])('parses %s as %s', (input, expected) => {
    const result = safeReturnUrl(input as string | null | undefined);
    expect(result === null).toBe(!expected);
  });
});

describe('useAuthStore', () => {
  beforeEach(() => {
    useAuthStore.getState().reset();
    localStorage.clear();
    sessionStorage.clear();
    document.cookie = 'antflow-csrf=csrf-value; path=/';
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    document.cookie = 'antflow-csrf=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
  });

  it('keeps accessToken in memory only', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(
      jsonResponse(200, { accessToken: 'mem-token', user: SAMPLE_USER }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await useAuthStore.getState().restore();
    expect(useAuthStore.getState().status).toBe('authenticated');
    expect(useAuthStore.getState().accessToken).toBe('mem-token');

    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(sessionStorage.getItem('accessToken')).toBeNull();
    expect(document.cookie.includes('mem-token')).toBe(false);
  });

  it('sends cookie credentials on restore and refresh', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, { accessToken: 'first-token', user: SAMPLE_USER }))
      .mockResolvedValueOnce(jsonResponse(200, { accessToken: 'second-token', user: SAMPLE_USER }));
    vi.stubGlobal('fetch', fetchMock);

    await useAuthStore.getState().restore();
    await useAuthStore.getState().refresh();

    expect(fetchMock).toHaveBeenCalledTimes(2);
    for (const call of fetchMock.mock.calls) {
      const init = (call?.[1] ?? {}) as RequestInit;
      expect(init.credentials).toBe('include');
      const headers = new Headers(init.headers as HeadersInit);
      expect(headers.get('X-CSRF-Token')).toBe('csrf-value');
    }
  });

  it('marks anonymous on refresh failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(emptyResponse(401)));
    await useAuthStore.getState().refresh().catch(() => undefined);
    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(useAuthStore.getState().accessToken).toBeNull();
  });

  it('logout clears state', async () => {
    useAuthStore.setState({
      status: 'authenticated',
      accessToken: 'tok',
      user: SAMPLE_USER,
    });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(emptyResponse(204)));

    await useAuthStore.getState().logout();
    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(useAuthStore.getState().user).toBeNull();
  });

  it('returns Authorization header only when token is present', () => {
    useAuthStore.setState({ status: 'authenticated', accessToken: 'abc', user: SAMPLE_USER });
    expect(useAuthStore.getState().authorizationHeader()).toEqual({ Authorization: 'Bearer abc' });

    useAuthStore.setState({ status: 'anonymous', accessToken: null, user: null });
    expect(useAuthStore.getState().authorizationHeader()).toEqual({});
  });
});
