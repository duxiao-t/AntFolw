import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
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
          </Routes>
        </MemoryRouter>
      </AppProviders>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  useAuthStore.getState().reset();
  vi.unstubAllGlobals();
});

describe('LoginPage', () => {
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
    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    await waitFor(() => {
      expect(screen.getByText('工作台目标页')).toBeInTheDocument();
    });
    expect(useAuthStore.getState().status).toBe('authenticated');
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
      expect(useAuthStore.getState().status).toBe('anonymous');
    });
    expect(screen.queryByText('工作台目标页')).not.toBeInTheDocument();
  });
});

void (null as ReactNode);
