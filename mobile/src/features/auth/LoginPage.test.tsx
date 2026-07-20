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
  it('uses brand title from BrandProvider and sets autocomplete attributes', () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    renderLogin('/login');
    expect(screen.getByText('登录 AntFlow')).toBeInTheDocument();
    const username = screen.getByPlaceholderText('请输入账号');
    const password = screen.getByPlaceholderText('请输入密码');
    expect(username.getAttribute('autocomplete')).toBe('username');
    expect(password.getAttribute('autocomplete')).toBe('current-password');
  });

  it('submits credentials and navigates to returnUrl on success', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ accessToken: 'mem', user: SAMPLE_USER }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();
    renderLogin('/login?returnUrl=%2Fworkbench');

    await user.type(screen.getByPlaceholderText('请输入账号'), 'admin');
    await user.type(screen.getByPlaceholderText('请输入密码'), 'ant.design');
    fireEvent.click(screen.getByRole('button', { name: '登录' }));

    await waitFor(() => {
      expect(screen.getByText('工作台目标页')).toBeInTheDocument();
    });
    expect(useAuthStore.getState().status).toBe('authenticated');
  });

  it('keeps auth anonymous after a 401 from the login endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code: 'INVALID_CREDENTIALS', message: 'bad' }), {
        status: 401,
        headers: { 'content-type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderLogin('/login');

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
