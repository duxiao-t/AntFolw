import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ProfilePage } from './ProfilePage';
import type { MobileBootstrap } from '../../shared/api/types';
import { useAuthStore } from '../auth/auth.store';

const BOOTSTRAP: MobileBootstrap = {
  user: {
    id: 7,
    username: 'admin',
    displayName: '管理员',
    department: '研发部',
    employeeNo: '000007',
    roles: ['admin'],
  },
  pendingCount: 3,
  unreadNotificationCount: 2,
  favoriteApps: [],
  recentProcesses: [],
  brandingVersion: 'tenant-2026-07-18',
};

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function renderProfile(fetchMock: ReturnType<typeof vi.fn>, queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
})) {
  vi.stubGlobal('fetch', fetchMock);
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/profile']}>
        <Routes>
          <Route path="/profile" element={<ProfilePage />} />
          <Route path="/login" element={<div>登录目标页</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  useAuthStore.getState().reset();
  vi.unstubAllGlobals();
});

describe('ProfilePage', () => {
  it('renders the profile summary from bootstrap without a second user request', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, BOOTSTRAP));
    renderProfile(fetchMock);

    await waitFor(() => {
      expect(screen.getByText('管理员')).toBeInTheDocument();
    });

    expect(screen.getByText((text) => text.includes('研发部') && text.includes('000007'))).toBeInTheDocument();
    expect(screen.queryByText((text) => text.includes('admin'))).not.toBeInTheDocument();
    expect(screen.queryByText('已发起')).not.toBeInTheDocument();
    expect(screen.queryByText('已处理')).not.toBeInTheDocument();
    expect(screen.queryByText('待办')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /我的草稿/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /消息中心/ })).toHaveTextContent('2');
    expect(screen.getByRole('button', { name: /账号安全/ })).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith('/api/mobile/bootstrap', expect.any(Object));
  });

  it('logs out to the login page and clears user-scoped query data', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('/api/auth/logout')) return new Response(null, { status: 204 });
      return jsonResponse(200, BOOTSTRAP);
    });
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    queryClient.setQueryData(['private', 'cached'], { stale: true });
    useAuthStore.setState({
      status: 'authenticated', accessToken: 'token', user: BOOTSTRAP.user,
      mobileBootstrap: BOOTSTRAP,
    });
    const user = userEvent.setup();
    renderProfile(fetchMock, queryClient);

    await user.click(await screen.findByRole('button', { name: /退出登录/ }));

    expect(await screen.findByText('登录目标页')).toBeInTheDocument();
    expect(useAuthStore.getState().status).toBe('anonymous');
    // 同 SecurityPage.test：clear() 与 navigate 在同一 tick，页面可能在被卸载前把它的查询
    // 再挂一次（空壳、无数据）。这条用例要钉的是"用户数据不残留"，所以断言数据而不是条数。
    expect(queryClient.getQueryData(['private', 'cached'])).toBeUndefined();
    expect(queryClient.getQueryCache().getAll()
      .filter((query) => query.state.data !== undefined)).toHaveLength(0);
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/api/auth/logout'))).toBe(true);
  });
});
