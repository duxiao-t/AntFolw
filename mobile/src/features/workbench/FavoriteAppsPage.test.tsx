import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoriteAppsPage } from './FavoriteAppsPage';
import { useFavoriteDraftStore } from './apps.store';
import { useAuthStore } from '../auth/auth.store';
import type { MobileApp, MobileUser } from '../../shared/api/types';

vi.mock('antd-mobile', () => ({
  Button: (props: { children?: React.ReactNode; onClick?: () => void; [k: string]: unknown }) => (
    <button type="button" onClick={props.onClick}>
      {props.children}
    </button>
  ),
  SafeArea: () => null,
}));

const APPS: MobileApp[] = Array.from({ length: 5 }, (_, i) => ({
  formId: i + 1,
  code: `app-${i + 1}`,
  name: `应用 ${i + 1}`,
  category: 'other',
  categoryLabel: '其他',
}));

const AUTH_USER: MobileUser = {
  id: 1,
  username: 'admin',
  displayName: '管理员',
  roles: ['admin'],
};

function setupFetch(favoriteApps: MobileApp[] = APPS.slice(0, 3)) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/api/mobile/preferences/apps') && init?.method === 'PUT') {
        return new Response(null, { status: 204 });
      }
      if (url.includes('/api/mobile/apps')) {
        return new Response(JSON.stringify(APPS), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        });
      }
      if (url.includes('/api/mobile/bootstrap')) {
        return new Response(
          JSON.stringify({
            user: AUTH_USER,
            pendingCount: 0,
            favoriteApps,
            recentProcesses: [],
            brandingVersion: 'test-1',
          }),
          { status: 200, headers: { 'content-type': 'application/json' } },
        );
      }
      return new Response(JSON.stringify({}), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      });
    }),
  );
}

function setupFailedSaveFetch() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/api/mobile/preferences/apps') && init?.method === 'PUT') {
        return new Response(JSON.stringify({ code: 'SAVE_FAILED', message: '保存失败' }), {
          status: 500,
          headers: { 'content-type': 'application/json' },
        });
      }
      if (url.includes('/api/mobile/apps')) {
        return new Response(JSON.stringify(APPS), {
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
}

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/apps/favorites']}>
        <FavoriteAppsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  useAuthStore.setState({ status: 'authenticated', accessToken: 't', user: AUTH_USER });
  useFavoriteDraftStore.setState({ ids: [], source: [], isDirty: false, initialized: false });
  vi.unstubAllGlobals();
});

describe('FavoriteAppsPage', () => {
  it('reorders favourites with up/down controls', async () => {
    setupFetch();
    useFavoriteDraftStore.setState({ ids: [1, 2, 3], source: [1, 2, 3], isDirty: false });
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('应用 1')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    const downButtons = await screen.findAllByLabelText('下移');
    const firstDownButton = downButtons[0];
    if (!firstDownButton) throw new Error('expected a down button');
    await user.click(firstDownButton);
    await waitFor(() => {
      expect(useFavoriteDraftStore.getState().ids).toEqual([2, 1, 3]);
    });
    expect(useFavoriteDraftStore.getState().isDirty).toBe(true);
  });

  it('initializes the draft from bootstrap favourites', async () => {
    setupFetch();
    renderPage();

    await waitFor(() => {
      expect(useFavoriteDraftStore.getState().ids).toEqual([1, 2, 3]);
    });
    expect(screen.getByText('应用 1')).toBeInTheDocument();
  });

  it('clears displayed apps when the last favourite is removed', async () => {
    setupFetch(APPS.slice(0, 1));
    useFavoriteDraftStore.setState({ ids: [1], source: [1], isDirty: false });
    vi.stubGlobal('confirm', vi.fn(() => true));
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('应用 1')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '移除' }));

    expect(screen.getByText('还没有常用应用')).toBeInTheDocument();
  });

  it('persists via PUT when the user saves', async () => {
    setupFetch();
    useFavoriteDraftStore.setState({ ids: [1, 2], source: [1], isDirty: true, initialized: true });
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('应用 1')).toBeInTheDocument();
    });

    const fetchMock = fetch as unknown as { mock: { calls: unknown[][] } };
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '完成' }));

    await waitFor(() => {
      const hit = fetchMock.mock.calls.find((entry) =>
        String(entry[0]).includes('/api/mobile/preferences/apps'),
      );
      expect(hit).toBeDefined();
    });
  });

  it('retains the draft state when save fails', async () => {
    setupFailedSaveFetch();
    useFavoriteDraftStore.setState({ ids: [1, 2], source: [1], isDirty: true });
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('应用 1')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '完成' }));

    await waitFor(() => {
      expect(screen.getByText('保存失败，请重试')).toBeInTheDocument();
    });
    expect(useFavoriteDraftStore.getState().ids).toEqual([1, 2]);
    expect(useFavoriteDraftStore.getState().isDirty).toBe(true);
  });

  it('keeps fallback entries visible when only part of the catalog resolves', async () => {
    setupFetch([APPS[0] as MobileApp]);
    useFavoriteDraftStore.setState({ ids: [1, 99], source: [1, 99], isDirty: false, initialized: true });
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('应用 1')).toBeInTheDocument();
    });
    expect(screen.getByText('应用 99')).toBeInTheDocument();
  });

  it('renders an error state when the catalog lookup fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.includes('/api/mobile/bootstrap')) {
          return new Response(
            JSON.stringify({
              user: AUTH_USER,
              pendingCount: 0,
              favoriteApps: APPS.slice(0, 2),
              recentProcesses: [],
              brandingVersion: 'test-1',
            }),
            { status: 200, headers: { 'content-type': 'application/json' } },
          );
        }
        if (url.includes('/api/mobile/apps')) {
          return new Response(JSON.stringify({ code: 'CATALOG_FAILED', message: '加载失败' }), {
            status: 500,
            headers: { 'content-type': 'application/json' },
          });
        }
        return new Response(JSON.stringify({}), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        });
      }),
    );
    renderPage();

    await waitFor(() => {
      expect(screen.getByText('常用应用加载失败')).toBeInTheDocument();
    });
  });

  it('marks the draft clean again when edits return to the source order', () => {
    useFavoriteDraftStore.setState({ ids: [1, 2], source: [1, 2], isDirty: false, initialized: true });

    useFavoriteDraftStore.getState().remove(2);
    expect(useFavoriteDraftStore.getState().isDirty).toBe(true);

    useFavoriteDraftStore.getState().add(2);
    expect(useFavoriteDraftStore.getState().ids).toEqual([1, 2]);
    expect(useFavoriteDraftStore.getState().isDirty).toBe(false);
  });

  it('caps pre-bootstrap source merge at eight favourites', () => {
    useFavoriteDraftStore.setState({
      ids: [4, 5, 6, 7, 8, 9, 10, 11],
      source: [],
      isDirty: true,
      initialized: false,
    });

    useFavoriteDraftStore.getState().syncSource([1, 2, 3]);

    expect(useFavoriteDraftStore.getState().ids).toEqual([1, 2, 3, 4, 5, 6, 7, 8]);
    expect(useFavoriteDraftStore.getState().ids).toHaveLength(8);
    expect(useFavoriteDraftStore.getState().isDirty).toBe(true);
  });
});
