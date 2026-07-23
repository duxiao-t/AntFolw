import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { MobileShell } from './MobileShell';
import { AppProviders } from './AppProviders';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useAuthStore } from '../features/auth/auth.store';
import type { MobileUser } from '../shared/api/types';

const AUTH_USER: MobileUser = {
  id: 1,
  username: 'admin',
  displayName: '管理员',
  roles: ['admin'],
};

function wrap(_ui: ReactNode, initialPath: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AppProviders>
        <MemoryRouter initialEntries={[initialPath]}>
          <Routes>
            <Route element={<MobileShell />}>
              <Route path="/workbench" element={<main data-testid="body-workbench">工作台</main>} />
              <Route path="/tasks" element={<main data-testid="body-tasks">待办</main>} />
              <Route path="/profile" element={<main data-testid="body-profile">我的</main>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AppProviders>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  useAuthStore.getState().reset();
});

describe('MobileShell', () => {
  it('renders exactly three navigation tabs', () => {
    useAuthStore.setState({ status: 'authenticated', accessToken: 't', user: AUTH_USER });
    wrap(null, '/workbench');
    expect(screen.getByTestId('tab-workbench')).toBeInTheDocument();
    expect(screen.getByTestId('tab-tasks')).toBeInTheDocument();
    expect(screen.getByTestId('tab-profile')).toBeInTheDocument();
  });

  it('mounts the active route body through the shell outlet', async () => {
    useAuthStore.setState({ status: 'authenticated', accessToken: 't', user: AUTH_USER });
    wrap(null, '/tasks');
    await waitFor(() => {
      expect(screen.getByTestId('body-tasks')).toBeInTheDocument();
    });
  });

  it('keeps the safe-area spacing class on the tab bar wrap', () => {
    useAuthStore.setState({ status: 'authenticated', accessToken: 't', user: AUTH_USER });
    wrap(null, '/workbench');
    expect(screen.getByTestId('mobile-shell')).toBeInTheDocument();
    const nav = screen.getByLabelText('主导航');
    expect(nav.className).toContain('af-tabbar');
  });
});
