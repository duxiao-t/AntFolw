import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ProfilePage } from './ProfilePage';
import type { MobileBootstrap } from '../../shared/api/types';

const BOOTSTRAP: MobileBootstrap = {
  user: {
    id: 7,
    username: 'admin',
    displayName: '管理员',
    roles: ['admin'],
  },
  pendingCount: 3,
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

function renderProfile(fetchMock: ReturnType<typeof vi.fn>) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  vi.stubGlobal('fetch', fetchMock);
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/profile']}>
        <ProfilePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ProfilePage', () => {
  it('renders the profile summary from bootstrap without a second user request', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, BOOTSTRAP));
    renderProfile(fetchMock);

    await waitFor(() => {
      expect(screen.getByText('管理员')).toBeInTheDocument();
    });

    expect(screen.getByText('admin')).toBeInTheDocument();
    expect(screen.getByText((text) => text.includes('3'))).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '草稿箱' })).toHaveAttribute(
      'href',
      '/tasks?status=draft',
    );
    expect(screen.getByRole('link', { name: '账号与安全' })).toHaveAttribute(
      'href',
      '/profile/security',
    );
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith('/api/mobile/bootstrap', expect.any(Object));
  });
});
