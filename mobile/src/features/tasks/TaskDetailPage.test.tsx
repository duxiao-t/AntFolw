import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { setAuthController } from '../../shared/api/auth';
import { TaskDetailPage } from './TaskDetailPage';
import { TaskCenterPage } from './TaskCenterPage';
import type { MobileTaskDetail } from './tasks.api';

const TASK_DETAIL: MobileTaskDetail = {
  task: {
    id: 401,
    instanceId: 9001,
    nodeId: 'a1',
    formCode: 'leave',
    formName: '请假申请',
    businessNo: '000000009001',
    applicantName: '张三',
    applicantEmployeeNo: '000007',
    applicantDepartment: '研发部',
    nodeName: '直属主管',
    taskType: 'APPROVAL',
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
  approvalSummary: { flowedCount: 2, completedCount: 1, processingCount: 1, complete: false },
  approvalRecords: [
    { id: 'submission', taskId: null, nodeId: 'root', nodeName: '提交申请', status: 'SUBMITTED', operatorName: '张三', employeeNo: '000007', department: '研发部', comment: null, receivedAt: '2026-07-21T08:55:00+08:00', completedAt: '2026-07-21T08:55:00+08:00' },
    { id: 'task-401', taskId: 401, nodeId: 'a1', nodeName: '直属主管', status: 'PROCESSING', operatorName: '李主管', employeeNo: '000008', department: '研发部', comment: null, receivedAt: '2026-07-21T09:00:00+08:00', completedAt: null },
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
  approvalSummary: { flowedCount: 2, completedCount: 2, processingCount: 0, complete: true },
  approvalRecords: TASK_DETAIL.approvalRecords.map((record) => record.taskId
    ? { ...record, status: 'APPROVED', completedAt: '2026-07-21T10:00:00+08:00' }
    : record),
};

function setupFetch(options: {
  detail?: typeof TASK_DETAIL;
  conflictOnApprove?: boolean;
  failRejectValidation?: boolean;
  fileDownloadFailure?: boolean;
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
      if (url.includes('/api/mobile/files/d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60/content')) {
        if (options.fileDownloadFailure) {
          return jsonResponse({
            code: 'FILE_STORAGE_FAILED',
            message: 'could not read object from MinIO',
          }, 422);
        }
        return new Response(new Blob(['%PDF-proof'], { type: 'application/pdf' }), {
          status: 200,
          headers: { 'content-type': 'application/pdf' },
        });
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
        expect(body.rejectToNodeId).toBeUndefined();
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
  setAuthController({
    authorizationHeader: () => ({}),
    refresh: async () => undefined,
    isAuthEndpoint: (path) => path.includes('/auth/'),
  });
  setupFetch();
});

describe('TaskDetailPage', () => {
  it('renders readonly form, files, timeline and allowed actions', async () => {
    renderDetail();

    expect(await screen.findByText('审批详情')).toBeInTheDocument();
    expect(screen.getAllByText((text) => text.includes('张三')).length).toBeGreaterThan(0);
    expect(screen.getByText('回家探亲')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '下载证明.pdf' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '下载证明.pdf' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '预览证明.pdf' })).not.toBeInTheDocument();
    expect(screen.getByText('审批中')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '同意' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '驳回' })).toBeInTheDocument();
  });

  it('downloads protected attachments through authenticated blob fetch', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const createObjectURL = vi.fn(() => 'blob:http://localhost/proof');
    const revokeObjectURL = vi.fn();
    Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: createObjectURL });
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: revokeObjectURL });
    const linkClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined);
    setAuthController({
      authorizationHeader: () => ({ Authorization: 'Bearer mobile-token' }),
      refresh: async () => undefined,
      isAuthEndpoint: (path) => path.includes('/auth/'),
    });
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>;

    renderDetail();
    await screen.findByRole('button', { name: '下载证明.pdf' });
    fetchMock.mockClear();

    await user.click(screen.getByRole('button', { name: '下载证明.pdf' }));

    await waitFor(() => expect(createObjectURL).toHaveBeenCalledWith(expect.any(Blob)));
    const contentCall = fetchMock.mock.calls.find(([input]) =>
      String(input).includes('/api/mobile/files/d2cecb38-11a8-4d2e-9f43-96ce6f4a7e60/content'));
    expect(contentCall).toBeTruthy();
    expect(contentCall?.[1]).toEqual(expect.objectContaining({ credentials: 'include' }));
    expect(new Headers(contentCall?.[1]?.headers).get('Authorization')).toBe('Bearer mobile-token');
    expect(linkClick).toHaveBeenCalled();
    expect(revokeObjectURL).not.toHaveBeenCalled();
    await act(async () => {
      vi.advanceTimersByTime(60_000);
    });
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:http://localhost/proof');
    linkClick.mockRestore();
    vi.useRealTimers();
  });

  it('shows a precise error when the MinIO object is missing', async () => {
    setupFetch({ fileDownloadFailure: true });
    renderDetail();
    await screen.findByRole('button', { name: '下载证明.pdf' });

    await userEvent.click(screen.getByRole('button', { name: '下载证明.pdf' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '附件原文件未写入 MinIO，请重新上传后再下载',
    );
  });

  it('hides action buttons when server returns empty allowedActions', async () => {
    setupFetch({ detail: READONLY_DETAIL });
    renderDetail();

    expect((await screen.findAllByText('已完成')).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: '同意' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '驳回' })).not.toBeInTheDocument();
  });

  it('allows optional approve comment and navigates back after success', async () => {
    renderDetail();
    await screen.findByRole('button', { name: '同意' });
    await userEvent.click(screen.getByRole('button', { name: '同意' }));

    const dialog = await screen.findByRole('dialog', { name: '同意审批' });
    await userEvent.type(within(dialog).getByPlaceholderText('请输入审批意见'), '同意申请');
    await userEvent.click(within(dialog).getByRole('button', { name: '确认同意' }));

    expect(await screen.findByRole('heading', { name: '需要你处理的审批' })).toBeInTheDocument();
  });

  it('renders editable fields and submits their values on approve', async () => {
    const editableDetail: typeof TASK_DETAIL = {
      ...TASK_DETAIL,
      processSnapshot: {
        id: 'root',
        type: 'ROOT',
        children: {
          id: 'a1',
          type: 'APPROVAL',
          props: {
            name: '直属主管',
            formPerms: [{ fieldId: 'reason', mode: 'EDITABLE' }],
          },
        },
      },
    };
    setupFetch({ detail: editableDetail });
    const user = userEvent.setup();
    renderDetail();

    await screen.findByRole('button', { name: '同意' });
    const input = await screen.findByLabelText('请假事由');
    await user.clear(input);
    await user.type(input, '回家探亲修改');
    await user.click(screen.getByRole('button', { name: '同意' }));
    const dialog = await screen.findByRole('dialog', { name: '同意审批' });
    await user.click(within(dialog).getByRole('button', { name: '确认同意' }));

    await waitFor(() => {
      const approveCall = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.find(
        ([input]) => String(input).includes('/api/mobile/tasks/401/approve'),
      );
      expect(approveCall).toBeTruthy();
      const body = JSON.parse(String(approveCall?.[1]?.body)) as Record<string, unknown>;
      expect(body.data).toEqual({ reason: '回家探亲修改' });
    });
  });

  it('shows an in-app preview button for image attachments', async () => {
    const detail: typeof TASK_DETAIL = {
      ...TASK_DETAIL,
      files: [
        {
          id: 'img-1',
          name: 'photo.png',
          contentType: 'image/png',
          size: 10,
          contentUrl: '/api/mobile/files/img-1/content',
        },
      ],
    };
    setupFetch({ detail });
    renderDetail();

    expect(await screen.findByRole('button', { name: '预览photo.png' })).toBeInTheDocument();
  });

  it('requires reject comment and lets the server choose the previous level', async () => {
    renderDetail();
    await screen.findByRole('button', { name: '驳回' });
    await userEvent.click(screen.getByRole('button', { name: '驳回' }));

    const dialog = await screen.findByRole('dialog', { name: '驳回审批' });
    await userEvent.click(within(dialog).getByRole('button', { name: '确认驳回' }));
    expect(within(dialog).getByText('请输入驳回原因（必填）')).toBeInTheDocument();

    await userEvent.type(within(dialog).getByPlaceholderText('请输入驳回原因'), '资料不全');
    await userEvent.click(within(dialog).getByRole('button', { name: '确认驳回' }));

    expect(await screen.findByRole('heading', { name: '需要你处理的审批' })).toBeInTheDocument();
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
    expect(await screen.findByRole('heading', { name: '需要你处理的审批' })).toBeInTheDocument();
  });
});
