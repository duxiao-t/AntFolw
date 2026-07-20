import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppProviders } from './AppProviders';
import { createTestRouter } from './router';
import { useAuthStore } from '../features/auth/auth.store';
import type { MobileBootstrap, MobileUser } from '../shared/api/types';

function wrapWithProviders(children: ReactNode, queryClient: QueryClient) {
  return (
    <QueryClientProvider client={queryClient}>
      <AppProviders>{children}</AppProviders>
    </QueryClientProvider>
  );
}

const AUTH_USER: MobileUser = {
  id: 1,
  username: 'admin',
  displayName: '管理员',
  roles: ['admin'],
};

const BOOTSTRAP: MobileBootstrap = {
  user: AUTH_USER,
  pendingCount: 0,
  favoriteApps: [],
  recentProcesses: [],
  brandingVersion: 'test-1',
};

beforeEach(() => {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/api/mobile/bootstrap')) {
        return new Response(JSON.stringify(BOOTSTRAP), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        });
      }
      return new Response(JSON.stringify({}), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      });
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  useAuthStore.getState().reset();
});

describe('router smoke tests for authenticated nested routes', () => {
  it('renders the workbench page after bootstrap resolves', async () => {
    useAuthStore.setState({
      status: 'authenticated',
      accessToken: 'test-token',
      user: AUTH_USER,
    });
    const router = createTestRouter('/workbench');
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    render(wrapWithProviders(<RouterProvider router={router} />, queryClient));
    await waitFor(
      () => {
        expect(screen.getByTestId('workbench')).toBeInTheDocument();
      },
      { timeout: 8000 },
    );
  });

  it.each([
    ['/tasks', '待办'],
    ['/profile', '我的'],
  ])('renders %s when authenticated', async (path: string, marker: string) => {
    useAuthStore.setState({
      status: 'authenticated',
      accessToken: 'test-token',
      user: AUTH_USER,
    });
    const router = createTestRouter(path);
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    render(wrapWithProviders(<RouterProvider router={router} />, queryClient));
    await waitFor(
      () => {
        const main = screen.getByRole('main');
        expect(within(main).getByText(marker)).toBeInTheDocument();
      },
      { timeout: 3000 },
    );
  });
});
