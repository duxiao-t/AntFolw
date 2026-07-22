import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TaskDetailPage } from './TaskDetailPage';
import { TaskCenterPage } from './TaskCenterPage';

const TASK_DETAIL = {
  task: {
    id: 401,
    instanceId: 9001,
    formName: '请假申请',
    applicantName: '张三',
    applicantDepartment: '研发部',
    nodeName: '直属主管',
    taskStatus: 'PENDING',
    instanceStatus: 'RUNNING',
    createdAt: '2026-07-21T09:00:00+08:00',
  },
  schema: [
    { id: 'reason', type: 'text', label: '请假事由', props: { required: true } },
    { id: 'days', type: 'number', label: '请假天数' },
  ],
  formData: { reason: '回家探亲', days: 2 },
  processSnapshot: {
    id: 'root',
    type: 'ROOT',
    children: {
      id: 'a1',
      type: 'APPROVAL',
      props: { name: '直属主管' },
    },
  },
  history: [
    {
      id: 1,
      fromNodeId: 'root',
      toNodeId: 'a1',
      action: 'ARRIVE',
      operatorId: 7,
      comment: null,
      createdAt: '2026-07-21T09:00:00+08:00',
    },
  ],
  allowedActions: ['APPROVE', 'REJECT'],
  rejectTargets: [{ nodeId: 'root', name: '发起人' }],
  files: [
    {
      id: 'd2cecb38-11a8-4d2e-9f43-96ce6f4a7e60',
      name: '证明.pdf',
      contentType: 'application/pdf',
      size: 1024,
      contentUrl: '/api/mobile/files/d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60/content',
    },
  ],
};

const READONLY_DETAIL = {
  ...TASK_DETAIL,
  task: {
    ...TASK_DETAIL.task,
    taskStatus: 'APPROVED',
    instanceStatus: 'APPROVED',
  },
  allowedActions: [] as string[],
};

function setupFetch(options: {
  detail?: typeof TASK_DETAIL;
  conflictOnApprove?: boolean;
  failRejectValidation?: boolean;
} = {}) {
  const detail = options.detail ?? TASK_DETAIL;
  let approveCount = 0;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input.toString();
      if (url.includes('/api/mobile/tasks/401') && !url.includes('/approve') && !url.includes('/reject')) {
        return jsonResponse(detail);
      }
      if (url.includes('/api/mobile/tasks/401/approve') && init?.method === 'POST') {
        approveCount += 1;
        if (options.conflictOnApprove && approveCount === 1) {
          return jsonResponse({ code: 'ALREADY_ACTED', message: '任务已被处理' }, 409);
        }
        const headers = new Headers(init.headers);
        expect(headers.get('Idempotency-Key')).toBeTruthy();
        return new Response(null, { status: 204 });
      }
      if (url.includes('/api/mobile/tasks/401/reject') && init?.method === 'POST') {
        const body = JSON.parse(String(init.body)) as { comment?: string; rejectToNodeId?: string };
        if (!body.comment) {
          return jsonResponse({ code: 'VALIDATION', message: 'comment required' }, 400);
        }
        if (options.failRejectValidation) {
          return jsonResponse({ code: 'VALIDATION', message: '驳回失败' }, 400);
        }
        expect(body.rejectToNodeId).toBe('root');
        return new Response(null, { status: 204 });
      }
      if (url.includes('/api/mobile/tasks?')) {
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

function renderDetail(initialPath = '/tasks/401?returnView=pending') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const router = createMemoryRouter(
    [
      { path: '/tasks', element: <TaskCenterPage /> },
      { path: '/tasks/:taskId', element: <TaskDetailPage /> },
    ],
    { initialEntries: [initialPath] },
  );
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.unstubAllGlobals();
  setupFetch();
});

describe('TaskDetailPage', () => {
  it('renders readonly form, files, timeline and allowed actions', async () => {
    renderDetail();

    expect(await screen.findByRole('heading', { name: '请假申请' })).toBeInTheDocument();
    expect(screen.getByText('张三 · 研发部')).toBeInTheDocument();
    expect(screen.getByText('回家探亲')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '证明.pdf' })).toHaveAttribute(
      'href',
      '/api/mobile/files/d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60/content',
    );
    expect(screen.getByText('到达')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '同意' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '驳回' })).toBeInTheDocument();
  });

  it('hides action buttons when server returns empty allowedActions', async () => {
    setupFetch({ detail: READONLY_DETAIL });
    renderDetail();

    expect(await screen.findByText('已同意')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '同意' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '驳回' })).not.toBeInTheDocument();
  });

  it('allows optional approve comment and navigates back after success', async () => {
    renderDetail();
    await screen.findByRole('button', { name: '同意' });
    await userEvent.click(screen.getByRole('button', { name: '同意' }));

    const dialog = await screen.findByRole('dialog', { name: '同意审批' });
    await userEvent.type(within(dialog).getByPlaceholderText('可选填写审批意见'), '同意申请');
    await userEvent.click(within(dialog).getByRole('button', { name: '确认同意' }));

    expect(await screen.findByRole('heading', { name: '任务中心' })).toBeInTheDocument();
  });

  it('requires reject comment and posts selected reject target', async () => {
    renderDetail();
    await screen.findByRole('button', { name: '驳回' });
    await userEvent.click(screen.getByRole('button', { name: '驳回' }));

    const dialog = await screen.findByRole('dialog', { name: '驳回审批' });
    await userEvent.click(within(dialog).getByRole('button', { name: '确认驳回' }));
    expect(within(dialog).getByText('请填写驳回原因')).toBeInTheDocument();

    await userEvent.type(within(dialog).getByPlaceholderText('请填写驳回原因'), '资料不全');
    await userEvent.click(within(dialog).getByRole('button', { name: '确认驳回' }));

    expect(await screen.findByRole('heading', { name: '任务中心' })).toBeInTheDocument();
  });

  it('handles 409 by showing notice and refetching readonly state', async () => {
    setupFetch({ conflictOnApprove: true, detail: TASK_DETAIL });
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>;
    renderDetail();
    await screen.findByRole('button', { name: '同意' });
    await userEvent.click(screen.getByRole('button', { name: '同意' }));
    const dialog = await screen.findByRole('dialog', { name: '同意审批' });
    await userEvent.click(within(dialog).getByRole('button', { name: '确认同意' }));

    expect(await screen.findByRole('status')).toHaveTextContent('任务状态已更新');
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: '同意审批' })).not.toBeInTheDocument();
    });
    // detail was refetched after conflict
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/api/mobile/tasks/401'))).toBe(true);
  });

  it('locks actions while mutation is pending', async () => {
    let resolveApprove: (() => void) | undefined;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === 'string' ? input : input.toString();
        if (url.includes('/api/mobile/tasks/401') && !url.includes('/approve')) {
          return jsonResponse(TASK_DETAIL);
        }
        if (url.includes('/approve') && init?.method === 'POST') {
          await new Promise<void>((resolve) => {
            resolveApprove = resolve;
          });
          return new Response(null, { status: 204 });
        }
        return jsonResponse({});
      }),
    );

    renderDetail();
    await screen.findByRole('button', { name: '同意' });
    await userEvent.click(screen.getByRole('button', { name: '同意' }));
    const dialog = await screen.findByRole('dialog', { name: '同意审批' });
    await userEvent.click(within(dialog).getByRole('button', { name: '确认同意' }));

    const loadingButtons = within(dialog).getAllByRole('button');
    expect(loadingButtons.some((button) => button.hasAttribute('disabled'))).toBe(true);
    expect(screen.getByRole('button', { name: '驳回' })).toBeDisabled();
    resolveApprove?.();
    expect(await screen.findByRole('heading', { name: '任务中心' })).toBeInTheDocument();
  });
});
