import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LoginPage } from './LoginPage';
import { useAuthStore } from './auth.store';
import { AppProviders } from '../../app/AppProviders';
import type { MobileUser } from '../../shared/api/types';

const SAMPLE_USER: MobileUser = {
  id: 1,
  username: 'admin',
  displayName: '管理员',
  roles: ['admin'],
};

function jsonResponse(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function renderLogin(initialPath: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AppProviders>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/workbench" element={<div>工作台目标页</div>} />
            <Route path="/tasks" element={<div>待办目标页</div>} />
          </Routes>
        </MemoryRouter>
      </AppProviders>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  useAuthStore.getState().reset();
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe('LoginPage', () => {
  it('restores a valid cookie session and opens the safe return URL', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/auth/refresh')) {
        return jsonResponse(200, { accessToken: 'restored', user: SAMPLE_USER });
      }
      if (url.includes('/api/public/auth/providers')) return jsonResponse(200, []);
      if (url.includes('/api/public/auth/wecom/status')) return jsonResponse(200, { oauthEnabled: false });
      return jsonResponse(200, {});
    });
    vi.stubGlobal('fetch', fetchMock);

    renderLogin('/login?returnUrl=%2Ftasks%3Fview%3Dpending');

    expect(await screen.findByText('待办目标页')).toBeInTheDocument();
    expect(useAuthStore.getState().status).toBe('authenticated');
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/authorize'))).toBe(false);
  });

  it('shows WeCom login without starting authorization before the user clicks it', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/public/auth/providers')) return jsonResponse(200, []);
      if (url.includes('/api/public/auth/wecom/status')) return jsonResponse(200, { oauthEnabled: true });
      return jsonResponse(401, { code: 'UNAUTHORIZED', message: 'no session' });
    });
    vi.stubGlobal('fetch', fetchMock);
    const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => undefined);
    let redirect: FrameRequestCallback | undefined;
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      redirect = callback;
      return 1;
    });
    const user = userEvent.setup();

    renderLogin('/login?returnUrl=https%3A%2F%2Fevil.example');

    const wecomLogin = await screen.findByRole('button', { name: '企业微信登录' });
    expect(assign).not.toHaveBeenCalled();
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/authorize'))).toBe(false);

    await user.click(wecomLogin);

    expect(screen.getByRole('button', { name: '正在进入企业微信' })).toBeDisabled();
    expect(assign).not.toHaveBeenCalled();
    act(() => redirect?.(0));
    expect(assign).toHaveBeenCalledWith(
      '/api/public/auth/wecom/authorize?returnUrl=%2Fmobile%2Fworkbench',
    );
  });

  it('keeps the WeCom entry visible but disabled when OAuth is not enabled', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/public/auth/providers')) return jsonResponse(200, []);
      if (url.includes('/api/public/auth/wecom/status')) return jsonResponse(200, { oauthEnabled: false });
      return jsonResponse(401, { code: 'UNAUTHORIZED', message: 'no session' });
    });
    vi.stubGlobal('fetch', fetchMock);

    renderLogin('/login');

    expect(await screen.findByRole('button', { name: '企业微信登录' })).toBeDisabled();
    expect(screen.getByText('企业微信登录未启用，请联系管理员')).toBeInTheDocument();
  });

  it('explains when the WeCom status cannot be loaded', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/public/auth/providers')) return jsonResponse(200, []);
      if (url.includes('/api/public/auth/wecom/status')) throw new Error('network unavailable');
      return jsonResponse(401, { code: 'UNAUTHORIZED', message: 'no session' });
    });
    vi.stubGlobal('fetch', fetchMock);

    renderLogin('/login');

    expect(await screen.findByRole('button', { name: '企业微信登录' })).toBeDisabled();
    expect(screen.getByText('企业微信登录暂不可用，请稍后重试')).toBeInTheDocument();
  });

  it('uses brand title from BrandProvider and sets autocomplete attributes', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/public/branding')) {
        return jsonResponse(200, {
          version: 'builtin-1',
          appName: 'AntFlow 审批',
          companyName: 'AntFlow',
          primaryColor: '#0b57d0',
          mobileHeaderTitle: '工作台',
          loginTitle: '登录 AntFlow',
          showLoginFooter: true,
          footerText: '© 2026 AntFlow',
        });
      }
      // Keep restore anonymous so the login form stays mounted.
      return jsonResponse(401, { code: 'UNAUTHORIZED', message: 'no session' });
    });
    vi.stubGlobal('fetch', fetchMock);
    renderLogin('/login');
    expect(await screen.findByText('登录 AntFlow')).toBeInTheDocument();
    const username = screen.getByPlaceholderText('请输入账号');
    const password = screen.getByPlaceholderText('请输入密码');
    expect(username.getAttribute('autocomplete')).toBe('username');
    expect(password.getAttribute('autocomplete')).toBe('current-password');
  });

  it('submits credentials and navigates to returnUrl on success', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/api/public/branding')) {
        return jsonResponse(200, {
          version: 'builtin-1',
          appName: 'AntFlow 审批',
          companyName: 'AntFlow',
          primaryColor: '#0b57d0',
          mobileHeaderTitle: '工作台',
          loginTitle: '登录 AntFlow',
          showLoginFooter: true,
          footerText: '© 2026 AntFlow',
        });
      }
      if (url.includes('/api/auth/login') && (init?.method ?? 'GET').toUpperCase() === 'POST') {
        return jsonResponse(200, { accessToken: 'mem', user: SAMPLE_USER });
      }
      return jsonResponse(401, { code: 'UNAUTHORIZED', message: 'no session' });
    });
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderLogin('/login?returnUrl=%2Fworkbench');

    await screen.findByPlaceholderText('请输入账号');
    await user.type(screen.getByPlaceholderText('请输入账号'), 'admin');
    await user.type(screen.getByPlaceholderText('请输入密码'), 'ant.design');
    await user.click(screen.getByLabelText('记住我'));
    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    await waitFor(() => {
      expect(screen.getByText('工作台目标页')).toBeInTheDocument();
    });
    expect(useAuthStore.getState().status).toBe('authenticated');
    expect(localStorage.getItem('antflow-mobile-remembered-username')).toBe('admin');
  });

  it('keeps auth anonymous after a 401 from the login endpoint', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/api/public/branding')) {
        return jsonResponse(200, {
          version: 'builtin-1',
          appName: 'AntFlow 审批',
          companyName: 'AntFlow',
          primaryColor: '#0b57d0',
          mobileHeaderTitle: '工作台',
          loginTitle: '登录 AntFlow',
          showLoginFooter: true,
          footerText: '© 2026 AntFlow',
        });
      }
      if (url.includes('/api/auth/login') && (init?.method ?? 'GET').toUpperCase() === 'POST') {
        return jsonResponse(401, { code: 'INVALID_CREDENTIALS', message: 'bad' });
      }
      return jsonResponse(401, { code: 'UNAUTHORIZED', message: 'no session' });
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderLogin('/login');

    await screen.findByPlaceholderText('请输入账号');
    await user.type(screen.getByPlaceholderText('请输入账号'), 'admin');
    await user.type(screen.getByPlaceholderText('请输入密码'), 'wrong');
    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('账号或密码错误');
    });
    expect(screen.queryByText('工作台目标页')).not.toBeInTheDocument();
  });

  it('keeps the password masked and shows an inline validation error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(401, { code: 'UNAUTHORIZED', message: 'no session' })));
    const user = userEvent.setup();
    renderLogin('/login');

    const password = await screen.findByPlaceholderText('请输入密码');
    expect(password).toHaveAttribute('type', 'password');
    await user.type(screen.getByPlaceholderText('请输入账号'), 'admin');
    await user.click(screen.getByRole('button', { name: '登录' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('请输入密码');
  });

  it('disables controls while login is loading', async () => {
    let resolveLogin: ((response: Response) => void) | undefined;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/api/auth/login') && (init?.method ?? 'GET').toUpperCase() === 'POST') {
        return new Promise<Response>((resolve) => {
          resolveLogin = resolve;
        });
      }
      return jsonResponse(401, { code: 'UNAUTHORIZED', message: 'no session' });
    });
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderLogin('/login');

    await user.type(await screen.findByPlaceholderText('请输入账号'), 'admin');
    await user.type(screen.getByPlaceholderText('请输入密码'), 'ant.design');
    await user.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByRole('button', { name: /登录中/ })).toBeDisabled();
    expect(screen.getByPlaceholderText('请输入账号')).toBeDisabled();
    resolveLogin?.(jsonResponse(200, { accessToken: 'mem', user: SAMPLE_USER }));
    await waitFor(() => {
      expect(screen.getByText('工作台目标页')).toBeInTheDocument();
    });
  });
});

void (null as ReactNode);
