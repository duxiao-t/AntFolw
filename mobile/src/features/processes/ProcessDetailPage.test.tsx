import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TaskCenterPage } from '../tasks/TaskCenterPage';
import { ProcessDetailPage } from './ProcessDetailPage';

const INSTANCE_DETAIL = {
  id: 9003,
  status: 'RUNNING',
  formName: '采购申请',
  schema: [{ id: 'item', type: 'text', label: '采购物品' }],
  formData: { item: '显示器' },
  processSnapshot: {
    id: 'root',
    type: 'ROOT',
    children: {
      id: 'a1',
      type: 'APPROVAL',
      props: { name: '部门审批' },
    },
  },
  history: [
    {
      id: 11,
      fromNodeId: 'root',
      toNodeId: 'a1',
      action: 'ARRIVE',
      operatorId: 7,
      comment: null,
      createdAt: '2026-07-21T08:00:00+08:00',
    },
  ],
  canWithdraw: true,
  files: [],
};

const WITHDRAWN_DETAIL = {
  ...INSTANCE_DETAIL,
  status: 'WITHDRAWN',
  canWithdraw: false,
};

function setupFetch(options: {
  detail?: typeof INSTANCE_DETAIL;
  conflictOnWithdraw?: boolean;
} = {}) {
  let detail = options.detail ?? INSTANCE_DETAIL;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/api/mobile/instances/9003') && !url.includes('/withdraw')) {
        return jsonResponse(detail);
      }
      if (url.includes('/api/mobile/instances/9003/withdraw') && init?.method === 'POST') {
        const headers = new Headers(init.headers);
        expect(headers.get('Idempotency-Key')).toBeTruthy();
        if (options.conflictOnWithdraw) {
          detail = WITHDRAWN_DETAIL;
          return jsonResponse({ code: 'ALREADY_ACTED', message: '流程已处理' }, 409);
        }
        return new Response(null, { status: 204 });
      }
      if (url.includes('/api/mobile/instances?') || url.includes('/api/mobile/tasks?')) {
        return jsonResponse({ items: [], hasMore: false });
      }
      return jsonResponse({});
    }),
  );
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function renderProcess(initialPath = '/processes/9003?returnView=process') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const router = createMemoryRouter(
    [
      { path: '/tasks', element: <TaskCenterPage /> },
      { path: '/processes/:instanceId', element: <ProcessDetailPage /> },
    ],
    { initialEntries: [initialPath] },
  );
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

function mockConfirm(result: boolean) {
  const confirmMock = vi.fn(() => result);
  Object.defineProperty(window, 'confirm', {
    configurable: true,
    writable: true,
    value: confirmMock,
  });
  return confirmMock;
}

beforeEach(() => {
  vi.unstubAllGlobals();
  mockConfirm(true);
  setupFetch();
});

describe('ProcessDetailPage', () => {
  it('renders process snapshot timeline and withdraw when allowed', async () => {
    renderProcess();

    expect(await screen.findByRole('heading', { name: '采购申请' })).toBeInTheDocument();
    expect(screen.getByText('显示器')).toBeInTheDocument();
    expect(screen.getByText('到达')).toBeInTheDocument();
    expect(screen.getByText(/部门审批/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '撤回' })).toBeInTheDocument();
  });

  it('hides withdraw when canWithdraw is false', async () => {
    setupFetch({ detail: WITHDRAWN_DETAIL });
    renderProcess();

    expect(await screen.findByText('已撤回')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '撤回' })).not.toBeInTheDocument();
  });

  it('confirms withdrawal and navigates back after success', async () => {
    const confirmMock = mockConfirm(true);
    renderProcess();
    await screen.findByRole('button', { name: '撤回' });
    await userEvent.click(screen.getByRole('button', { name: '撤回' }));

    expect(confirmMock).toHaveBeenCalled();
    expect(await screen.findByRole('heading', { name: '任务中心' })).toBeInTheDocument();
  });

  it('does not withdraw when confirmation is cancelled', async () => {
    mockConfirm(false);
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/api/mobile/instances/9003') && !url.includes('/withdraw')) {
        return jsonResponse(INSTANCE_DETAIL);
      }
      throw new Error(`unexpected request ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    renderProcess();
    await screen.findByRole('button', { name: '撤回' });
    await userEvent.click(screen.getByRole('button', { name: '撤回' }));

    expect(fetchMock.mock.calls.every(([input]) => !String(input).includes('/withdraw'))).toBe(true);
  });

  it('handles ALREADY_ACTED by refetching and showing status notice', async () => {
    setupFetch({ conflictOnWithdraw: true });
    renderProcess();
    await screen.findByRole('button', { name: '撤回' });
    await userEvent.click(screen.getByRole('button', { name: '撤回' }));

    expect(await screen.findByRole('status')).toHaveTextContent('流程状态已更新');
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: '撤回' })).not.toBeInTheDocument();
    });
  });

  it('shows unknown historical node ids when snapshot cannot map them', async () => {
    setupFetch({
      detail: {
        ...INSTANCE_DETAIL,
        history: [
          {
            id: 99,
            fromNodeId: 'ghost-node',
            toNodeId: 'a1',
            action: 'ARRIVE',
            operatorId: 7,
            comment: null,
            createdAt: '2026-07-21T08:00:00+08:00',
          },
        ],
      },
    });
    renderProcess();

    expect(await screen.findByText(/ghost-node/)).toBeInTheDocument();
  });
});
