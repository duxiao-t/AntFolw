import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TaskCenterPage } from '../tasks/TaskCenterPage';
import { ProcessDetailPage } from './ProcessDetailPage';
import type { MobileInstanceDetail } from './processes.api';

const INSTANCE_DETAIL: MobileInstanceDetail = {
  visibility: 'FULL',
  id: 9003,
  status: 'RUNNING',
  formName: '采购申请',
  businessNo: '000000009003',
  applicantName: '张三',
  applicantEmployeeNo: '000007',
  applicantDepartment: '研发部',
  startedAt: '2026-07-20T09:00:00+08:00',
  currentNodeName: '部门审批',
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
  approvalSummary: { flowedCount: 2, completedCount: 1, processingCount: 1, complete: false },
  approvalRecords: [
    { id: 'submission', taskId: null, nodeId: 'root', nodeName: '提交申请', status: 'SUBMITTED', operatorName: '张三', employeeNo: '000007', department: '研发部', comment: null, receivedAt: '2026-07-20T09:00:00+08:00', completedAt: '2026-07-20T09:00:00+08:00' },
    { id: 'task-11', taskId: 11, nodeId: 'a1', nodeName: '部门审批', status: 'PROCESSING', operatorName: '李经理', employeeNo: '000008', department: '研发部', comment: null, receivedAt: '2026-07-21T08:00:00+08:00', completedAt: null },
  ],
};

const WITHDRAWN_DETAIL = {
  ...INSTANCE_DETAIL,
  status: 'WITHDRAWN',
  currentNodeName: null,
  canWithdraw: false,
  approvalSummary: { flowedCount: 2, completedCount: 1, processingCount: 1, complete: false },
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

    expect(await screen.findByText('审批详情')).toBeInTheDocument();
    expect(await screen.findByText('表单详情')).toBeInTheDocument();
    expect(screen.getAllByText('张三').length).toBeGreaterThan(0);
    expect(screen.getAllByText('研发部').length).toBeGreaterThan(0);
    expect(screen.getByText('2026/7/20 09:00:00')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '分享' })).toBeInTheDocument();
    expect(screen.getByText('采购物品')).toBeInTheDocument();
    expect(screen.getByText('显示器')).toBeInTheDocument();
    expect(screen.getByText('审批中', { selector: '.approval-record-card__status' })).toBeInTheDocument();
    expect(screen.getAllByText(/部门审批/).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: '撤回流程' })).toBeInTheDocument();
  });

  it('renders started process attachments as protected download buttons', async () => {
    setupFetch({
      detail: {
        ...INSTANCE_DETAIL,
        schema: [...(INSTANCE_DETAIL.schema ?? []), { id: 'quote', type: 'file_upload', label: '报价单' }],
        formData: {
          ...INSTANCE_DETAIL.formData,
          quote: [
            {
              id: 'af5f8d74-9c79-4d2c-9c41-f2b0cf1e82f0',
              name: '报价单.pdf',
              contentType: 'application/pdf',
              size: 2048,
              contentUrl: '/api/mobile/files/af5f8d74-9c79-4d2c-9c41-f2b0cf1e82f0/content',
            },
          ],
        },
        files: [],
      },
    });

    renderProcess();

    expect(await screen.findByRole('button', { name: '下载报价单.pdf' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '下载报价单.pdf' })).not.toBeInTheDocument();
  });

  it('renders all attachments inside form fields with download actions', async () => {
    setupFetch({
      detail: {
        ...INSTANCE_DETAIL,
        schema: [
          ...(INSTANCE_DETAIL.schema ?? []),
          { id: 'photo', type: 'image_upload', label: '图片' },
          { id: 'quote', type: 'file_upload', label: '报价单' },
        ],
        formData: {
          ...INSTANCE_DETAIL.formData,
          photo: [
            {
              id: 'img-1',
              name: 'photo.png',
              contentType: 'image/png',
              size: 2048,
              contentUrl: '/api/mobile/files/img-1/content',
            },
          ],
          quote: [
            {
              id: 'pdf-1',
              name: '报价单.pdf',
              contentType: 'application/pdf',
              size: 2048,
              contentUrl: '/api/mobile/files/pdf-1/content',
            },
          ],
        },
        files: [],
      },
    });

    renderProcess();

    expect(await screen.findByRole('img', { name: 'photo.png' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '下载photo.png' })).toBeInTheDocument();
    expect(screen.getByText('2 KB · 图片')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '下载报价单.pdf' })).toBeInTheDocument();
    expect(screen.getByText('2 KB · 文件')).toBeInTheDocument();
    expect(screen.queryByText('暂无附件')).not.toBeInTheDocument();
  });

  it('renders media previews inside the readonly form', async () => {
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:media'),
      revokeObjectURL: vi.fn(),
    });
    setupFetch({
      detail: {
        ...INSTANCE_DETAIL,
        schema: [{ id: 'photo', type: 'image_upload', label: '图片' }],
        formData: {
          photo: [
            {
              id: 'p1',
              name: 'a.png',
              contentUrl: '/api/mobile/files/p1/content',
              contentType: 'image/png',
              size: 1,
            },
          ],
        },
        files: [],
      },
    });

    renderProcess();

    expect(await screen.findByRole('img', { name: 'a.png' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '下载a.png' })).toBeInTheDocument();
    expect(screen.queryByText('暂无附件')).not.toBeInTheDocument();
  });

  it('hides withdraw when canWithdraw is false', async () => {
    setupFetch({ detail: WITHDRAWN_DETAIL });
    renderProcess();

    expect((await screen.findAllByText('已撤回')).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: '撤回流程' })).not.toBeInTheDocument();
  });

  it('renders summary visibility without form data or actions', async () => {
    setupFetch({
      detail: {
        ...INSTANCE_DETAIL,
        visibility: 'SUMMARY',
        formName: null,
        businessNo: null,
        schema: null,
        formData: null,
        processSnapshot: null,
        canWithdraw: false,
        files: [],
      },
    });
    renderProcess();

    expect(await screen.findByText('流程摘要')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('当前账号仅可查看流程摘要');
    expect(screen.getByText('提交申请')).toBeInTheDocument();
    expect(screen.queryByText('表单详情')).not.toBeInTheDocument();
    expect(screen.queryByText('采购物品')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '分享' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '撤回流程' })).not.toBeInTheDocument();
  });

  it('confirms withdrawal and navigates back after success', async () => {
    const confirmMock = mockConfirm(true);
    renderProcess();
    await screen.findByRole('button', { name: '撤回流程' });
    await userEvent.click(screen.getByRole('button', { name: '撤回流程' }));

    expect(confirmMock).toHaveBeenCalledWith(
      expect.stringContaining('原流程和单号会保留'),
    );
    expect(
      await screen.findByRole('heading', { name: '需要你处理的审批' }),
    ).toBeInTheDocument();
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
    await screen.findByRole('button', { name: '撤回流程' });
    await userEvent.click(screen.getByRole('button', { name: '撤回流程' }));

    expect(fetchMock.mock.calls.every(([input]) => !String(input).includes('/withdraw'))).toBe(true);
  });

  it('handles ALREADY_ACTED by refetching and showing status notice', async () => {
    setupFetch({ conflictOnWithdraw: true });
    renderProcess();
    await screen.findByRole('button', { name: '撤回流程' });
    await userEvent.click(screen.getByRole('button', { name: '撤回流程' }));

    expect(await screen.findByRole('status')).toHaveTextContent('流程状态已更新');
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: '撤回流程' })).not.toBeInTheDocument();
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
        approvalRecords: [
          ...INSTANCE_DETAIL.approvalRecords.slice(0, 1),
          { ...INSTANCE_DETAIL.approvalRecords[1]!, nodeId: 'ghost-node', nodeName: 'ghost-node' },
        ],
      },
    });
    renderProcess();

    expect(await screen.findByText(/ghost-node/)).toBeInTheDocument();
  });
});
