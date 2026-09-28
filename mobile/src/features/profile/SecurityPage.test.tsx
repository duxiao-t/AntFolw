import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SecurityPage } from './SecurityPage';
import { useAuthStore } from '../auth/auth.store';
import type { DeviceSession, MobileUser } from '../../shared/api/types';

const USER: MobileUser = {
  id: 7,
  username: 'admin',
  displayName: '管理员',
  roles: ['admin'],
};

const SESSIONS: DeviceSession[] = [
  {
    id: 'current',
    deviceName: 'Chrome Windows',
    platform: 'browser',
    lastActiveAt: '2026-07-20T08:00:00Z',
    isCurrent: true,
  },
  {
    id: 'phone',
    deviceName: 'iPhone Safari',
    platform: 'browser',
    lastActiveAt: '2026-07-19T08:00:00Z',
    isCurrent: false,
  },
];

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function emptyResponse(status: number): Response {
  return new Response(null, { status });
}

function renderSecurity(fetchMock: ReturnType<typeof vi.fn>, queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
})) {
  vi.stubGlobal('fetch', fetchMock);
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter initialEntries={['/profile/security']}>
          <Routes>
            <Route path="/profile/security" element={<SecurityPage />} />
            <Route path="/login" element={<div>登录页</div>} />
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    ),
  };
}

beforeEach(() => {
  useAuthStore.setState({
    status: 'authenticated',
    accessToken: 'token',
    user: USER,
  });
  localStorage.setItem('antflow-mobile:drafts:7', '{"saved":true}');
  localStorage.setItem('antflow-mobile:drafts:8', '{"saved":true}');
  localStorage.setItem('af:recovery:7:leave:new', '{"saved":true}');
  localStorage.setItem('af:recovery:7:expense:draft-1', '{"saved":true}');
  localStorage.setItem('af:recovery:8:leave:new', '{"saved":true}');
});

afterEach(() => {
  useAuthStore.getState().reset();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('SecurityPage', () => {
  it('lists sessions and hides WeCom binding while the feature flag is false', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, SESSIONS));
    renderSecurity(fetchMock);

    fireEvent.click(await screen.findByRole('button', { name: /登录设备/ }));
    expect(await screen.findByText('Chrome Windows')).toBeInTheDocument();
    expect(screen.getByText('当前设备')).toBeInTheDocument();
    expect(screen.getByText('iPhone Safari')).toBeInTheDocument();
    expect(screen.queryByText('绑定企业微信')).not.toBeInTheDocument();
    expect(screen.queryByText('已开启')).not.toBeInTheDocument();
    expect(screen.queryByText('较高')).not.toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledWith('/api/auth/sessions', expect.any(Object));
  });

  it('revokes one non-current device and refreshes the session list', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, SESSIONS))
      .mockResolvedValueOnce(emptyResponse(204))
      .mockResolvedValueOnce(jsonResponse(200, [SESSIONS[0]]));
    renderSecurity(fetchMock);

    fireEvent.click(await screen.findByRole('button', { name: /登录设备/ }));
    await screen.findByText('iPhone Safari');
    fireEvent.click(screen.getByRole('button', { name: '移除' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/auth/sessions/phone',
        expect.objectContaining({ method: 'DELETE' }),
      );
    });
    expect(await screen.findByText('Chrome Windows')).toBeInTheDocument();
  });

  it('logs out the current device and clears user-scoped recovery drafts', async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    queryClient.setQueryData(['cached'], 'value');
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(200, SESSIONS))
      .mockResolvedValueOnce(emptyResponse(204))
      .mockResolvedValueOnce(emptyResponse(204));
    renderSecurity(fetchMock, queryClient);

    await screen.findByRole('button', { name: '退出全部设备' });
    fireEvent.click(screen.getByRole('button', { name: '退出全部设备' }));

    await waitFor(() => {
      expect(screen.getByText('登录页')).toBeInTheDocument();
    });
    expect(useAuthStore.getState().status).toBe('anonymous');
    // 登出会 clear 缓存，但 clear() 与 navigate 在同一个 tick：外部 store 的通知与路由跳转
    // 谁先被调度，决定了 SecurityPage 会不会在卸载前把它的 session 查询再挂一次，
    // 于是缓存里可能留下一个**没有数据**的空壳（CI 上比本地更容易命中）。
    // 这条用例要钉的是"用户数据不残留"，所以断言数据而不是条数。
    expect(queryClient.getQueryData(['cached'])).toBeUndefined();
    expect(queryClient.getQueryCache().getAll()
      .filter((query) => query.state.data !== undefined)).toHaveLength(0);
    expect(localStorage.getItem('antflow-mobile:drafts:7')).toBeNull();
    expect(localStorage.getItem('antflow-mobile:drafts:8')).toBe('{"saved":true}');
    expect(localStorage.getItem('af:recovery:7:leave:new')).toBeNull();
    expect(localStorage.getItem('af:recovery:7:expense:draft-1')).toBeNull();
    expect(localStorage.getItem('af:recovery:8:leave:new')).toBe('{"saved":true}');
  });
});
