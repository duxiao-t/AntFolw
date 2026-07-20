import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AppCatalogPage } from './AppCatalogPage';
import { useFavoriteDraftStore } from './apps.store';
import { useAuthStore } from '../auth/auth.store';
import type { MobileApp, MobileUser } from '../../shared/api/types';

const APPS: MobileApp[] = Array.from({ length: 12 }, (_, i) => ({
  formId: i + 1,
  code: `app-${i + 1}`,
  name: i === 0 ? '请假申请' : i === 1 ? '报销审批' : i === 2 ? '加班申请' : `通用应用 ${i + 1}`,
  category: i % 3 === 0 ? 'hr' : i % 3 === 1 ? 'finance' : '',
  categoryLabel: i % 3 === 0 ? '人事' : i % 3 === 1 ? '财务' : '',
}));

const AUTH_USER: MobileUser = {
  id: 1,
  username: 'admin',
  displayName: '管理员',
  roles: ['admin'],
};

function setupFetch(apps: MobileApp[]) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/api/mobile/apps')) {
        const params = new URL(url, 'http://localhost').searchParams;
        const keyword = params.get('keyword') ?? '';
        const category = params.get('category');
        const filtered = apps.filter((app) => {
          const matchesKeyword = keyword ? app.name.includes(keyword) : true;
          const appCategory = app.category || 'other';
          const matchesCategory = category ? appCategory === category : true;
          return matchesKeyword && matchesCategory;
        });
        return new Response(JSON.stringify(filtered), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        });
      }
      if (url.includes('/api/mobile/bootstrap')) {
        return new Response(
          JSON.stringify({
            user: AUTH_USER,
            pendingCount: 0,
            favoriteApps: apps.slice(0, 2),
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

function renderCatalog() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/apps']}>
        <AppCatalogPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  useAuthStore.setState({ status: 'authenticated', accessToken: 't', user: AUTH_USER });
  useFavoriteDraftStore.setState({ ids: [], source: [], isDirty: false, initialized: false });
  vi.unstubAllGlobals();
});

describe('AppCatalogPage', () => {
  it('renders apps and supports search filtering', async () => {
    setupFetch(APPS);
    renderCatalog();

    await waitFor(() => {
      expect(screen.getByText('请假申请')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.type(screen.getByPlaceholderText('搜索应用'), '报销');

    await waitFor(() => {
      expect(screen.getByText('报销审批')).toBeInTheDocument();
      expect(screen.queryByText('请假申请')).not.toBeInTheDocument();
    });
  });

  it('renders category tabs and filters by selected category', async () => {
    setupFetch(APPS);
    renderCatalog();

    await waitFor(() => {
      expect(screen.getByText('请假申请')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('tab', { name: '财务' }));

    await waitFor(() => {
      expect(screen.getByText('报销审批')).toBeInTheDocument();
      expect(screen.queryByText('请假申请')).not.toBeInTheDocument();
    });
  });

  it('maps uncategorized apps to the other category tab', async () => {
    setupFetch(APPS);
    renderCatalog();

    await waitFor(() => {
      expect(screen.getByText('请假申请')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('tab', { name: '其他' }));

    await waitFor(() => {
      expect(screen.getByText('加班申请')).toBeInTheDocument();
      expect(screen.queryByText('请假申请')).not.toBeInTheDocument();
    });
    const fetchMock = fetch as unknown as { mock: { calls: unknown[][] } };
    const calledOther = fetchMock.mock.calls.some((entry) =>
      String(entry[0]).includes('category=other'),
    );
    expect(calledOther).toBe(true);
  });

  it('initializes selected favourites from bootstrap before adding more', async () => {
    setupFetch(APPS);
    renderCatalog();

    await waitFor(() => {
      expect(useFavoriteDraftStore.getState().ids).toEqual([1, 2]);
    });

    const user = userEvent.setup();
    await user.click(screen.getByText('加班申请'));

    expect(useFavoriteDraftStore.getState().ids).toEqual([1, 2, 3]);
  });

  it('merges a pre-bootstrap selection with existing server favourites', async () => {
    let resolveBootstrap: ((response: Response) => void) | undefined;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.includes('/api/mobile/apps')) {
          return new Response(JSON.stringify(APPS), {
            status: 200,
            headers: { 'content-type': 'application/json' },
          });
        }
        if (url.includes('/api/mobile/bootstrap')) {
          return new Promise<Response>((resolve) => {
            resolveBootstrap = resolve;
          });
        }
        return new Response(JSON.stringify({}), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        });
      }),
    );
    renderCatalog();

    await waitFor(() => {
      expect(screen.getByText('加班申请')).toBeInTheDocument();
    });

    const user = userEvent.setup();
    await user.click(screen.getByText('加班申请'));
    expect(useFavoriteDraftStore.getState().ids).toEqual([3]);

    resolveBootstrap?.(
      new Response(
        JSON.stringify({
          user: AUTH_USER,
          pendingCount: 0,
          favoriteApps: APPS.slice(0, 2),
          recentProcesses: [],
          brandingVersion: 'test-1',
        }),
        { status: 200, headers: { 'content-type': 'application/json' } },
      ),
    );

    await waitFor(() => {
      expect(useFavoriteDraftStore.getState().ids).toEqual([1, 2, 3]);
    });
    expect(useFavoriteDraftStore.getState().isDirty).toBe(true);
  });

  it('blocks selecting a ninth favourite and surfaces the limit message', async () => {
    setupFetch(APPS);
    renderCatalog();

    await waitFor(() => {
      expect(screen.getByText('请假申请')).toBeInTheDocument();
    });

    useFavoriteDraftStore.setState({
      ids: [1, 4, 5, 6, 7, 8, 9, 10],
      source: [1, 4, 5, 6, 7, 8, 9, 10],
      isDirty: true,
    });
    expect(useFavoriteDraftStore.getState().ids).toHaveLength(8);

    const user = userEvent.setup();
    await user.click(screen.getByText('加班申请'));

    expect(useFavoriteDraftStore.getState().ids).toHaveLength(8);
    expect(screen.getByText('最多添加 8 个常用应用')).toBeInTheDocument();
  });
});
