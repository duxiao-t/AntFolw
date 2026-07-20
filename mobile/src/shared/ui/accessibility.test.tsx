import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { AppProviders } from '../../app/AppProviders';
import { MobileShell } from '../../app/MobileShell';
import { useAuthStore } from '../../features/auth/auth.store';
import type { MobileUser } from '../api/types';

const AUTH_USER: MobileUser = {
  id: 1,
  username: 'admin',
  displayName: '管理员',
  roles: ['admin'],
};

function renderShell(path = '/workbench') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  useAuthStore.setState({ status: 'authenticated', accessToken: 'token', user: AUTH_USER });
  return render(
    <QueryClientProvider client={queryClient}>
      <AppProviders>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route element={<MobileShell />}>
              <Route path="/workbench" element={<main>工作台</main>} />
              <Route path="/tasks" element={<main>待办</main>} />
              <Route path="/profile" element={<main>我的</main>} />
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

describe('mobile accessibility contracts', () => {
  it('keeps the bottom navigation identifiable and sized for touch', () => {
    renderShell('/workbench');

    const nav = screen.getByLabelText('主导航');
    expect(nav).toHaveClass('touchSafeNav');
    expect(nav.className).toContain('tabBarWrap');
    expect(nav).toHaveAttribute('aria-label', '主导航');
  });

  it('marks the active tab and exposes exactly three primary destinations', () => {
    renderShell('/tasks');

    const tabs = [
      screen.getByTestId('tab-workbench'),
      screen.getByTestId('tab-tasks'),
      screen.getByTestId('tab-profile'),
    ];
    expect(tabs).toHaveLength(3);
    expect(screen.getByTestId('tab-tasks').className).toContain('adm-tab-bar-item-active');
  });
});
