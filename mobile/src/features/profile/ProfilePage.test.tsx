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

    expect(screen.getByText((text) => text.includes('admin'))).toBeInTheDocument();
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
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/api/auth/logout'))).toBe(true);
  });
});
