import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { RouterProvider } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { AppProviders } from './AppProviders';
import { createTestRouter } from './router';
import { useAuthStore } from '../features/auth/auth.store';
import type { MobileUser } from '../shared/api/types';

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

afterEach(() => {
  useAuthStore.getState().reset();
});

describe('router smoke tests for authenticated nested routes', () => {
  it.each([
    ['/workbench', '工作台'],
    ['/tasks', '待办'],
    ['/profile', '我的'],
  ])('renders %s when authenticated', async (path: string, title: string) => {
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
        expect(screen.getByText(title)).toBeInTheDocument();
      },
      { timeout: 3000 },
    );
  });
});
