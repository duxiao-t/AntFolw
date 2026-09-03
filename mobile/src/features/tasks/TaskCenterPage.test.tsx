import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TaskCenterPage } from './TaskCenterPage';

const PENDING_TASKS = [
  {
    id: 401,
    instanceId: 9001,
    nodeId: 'a1',
    formName: '请假申请',
    applicantName: '张三',
    applicantDepartment: '研发部',
    nodeName: '直属主管',
    taskStatus: 'PENDING',
    instanceStatus: 'RUNNING',
    createdAt: '2026-07-21T09:00:00+08:00',
  },
];

const NEXT_PENDING_TASKS = [
  {
    ...PENDING_TASKS[0],
    id: 403,
    formName: '加班申请',
    nodeName: '经理审批',
  },
];

const DONE_TASKS = [
  {
    id: 402,
    instanceId: 9002,
    nodeId: 'a3',
    formName: '报销申请',
    applicantName: '李四',
    applicantDepartment: '财务部',
    nodeName: '财务复核',
    taskStatus: 'APPROVED',
    instanceStatus: 'APPROVED',
    createdAt: '2026-07-20T10:00:00+08:00',
  },
];

const STARTED_PROCESSES = [
  {
    kind: 'WORKFLOW',
    id: 9003,
    status: 'RUNNING',
    formName: '采购申请',
    currentNodeName: '部门审批',
    startedAt: '2026-07-19T11:00:00+08:00',
    finishedAt: null,
  },
];

const DIRECT_SUBMISSIONS = [
  {
    kind: 'DIRECT',
    id: 7001,
    status: 'SUBMITTED',
    formName: '设备点检表',
    businessNo: 'CHECK-20260903-001',
    startedAt: '2026-07-20T11:00:00+08:00',
    finishedAt: '2026-07-20T11:00:00+08:00',
  },
];

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function setupFetch(options: { failDone?: boolean; emptyPending?: boolean; pagedPending?: boolean } = {}) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL(String(input), 'http://localhost');
      if (url.pathname === '/api/mobile/tasks') {
        expect(url.searchParams.get('size')).toBe('20');
        const page = url.searchParams.get('page');
        const view = url.searchParams.get('view');
        if (view === 'done') {
          if (options.failDone) {
            return jsonResponse({ code: 'SERVER_ERROR', message: '加载失败' }, 500);
          }
          return jsonResponse({ items: DONE_TASKS, hasMore: false });
        }
        return jsonResponse({
          items: page === '2' ? NEXT_PENDING_TASKS : options.emptyPending ? [] : PENDING_TASKS,
          hasMore: Boolean(options.pagedPending) && page !== '2',
        });
      }
      if (url.pathname === '/api/mobile/initiated') {
        expect(url.searchParams.get('page')).toBe('1');
        expect(url.searchParams.get('size')).toBe('20');
        return jsonResponse({ items: [...STARTED_PROCESSES, ...DIRECT_SUBMISSIONS], hasMore: false });
      }
      return jsonResponse({});
    }),
  );
}

function renderTaskCenter(initialPath = '/tasks') {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const router = createMemoryRouter(
    [
      { path: '/tasks', element: <TaskCenterPage /> },
      { path: '/tasks/:taskId', element: <h1>任务详情</h1> },
      { path: '/submissions/:submissionId', element: <h1>填报详情</h1> },
    ],
    { initialEntries: [initialPath] },
  );
  const result = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { ...result, router };
}

beforeEach(() => {
  vi.unstubAllGlobals();
  setupFetch();
});

describe('TaskCenterPage', () => {
  it('shows pending tasks by default with task and instance status separated', async () => {
    renderTaskCenter();

    expect(await screen.findByRole('heading', { name: '需要你处理的审批' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '待我处理' })).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByText('请假申请')).toBeInTheDocument();
    const pendingCard = screen.getByRole('link', { name: /请假申请/ });
    expect(within(pendingCard).getByText((text) => text.includes('张三'))).toBeInTheDocument();
    expect(within(pendingCard).getByText('待审批')).toBeInTheDocument();
    expect(pendingCard).toHaveAttribute(
      'href',
      '/tasks/401?returnView=pending',
    );
    await userEvent.click(pendingCard);
    expect(await screen.findByRole('heading', { name: '任务详情' })).toBeInTheDocument();
  });

  it('restores done view and filters from URL, then keeps filters when switching tabs', async () => {
    const { router } = renderTaskCenter('/tasks?view=done&keyword=报销&status=APPROVED');

    expect(await screen.findByText('报销申请')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '已处理' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('searchbox', { name: '搜索申请人或表单名' })).toHaveValue('报销');
    expect(screen.getByRole('button', { name: '通过' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByRole('button', { name: '跳过' })).not.toBeInTheDocument();
    const doneCard = screen.getByRole('link', { name: /报销申请/ });
    expect(within(doneCard).getByText('已完成')).toBeInTheDocument();
    expect(doneCard).toHaveAttribute(
      'href',
      '/tasks/402?returnView=done&returnKeyword=%E6%8A%A5%E9%94%80&returnStatus=APPROVED',
    );

    await userEvent.click(screen.getByRole('tab', { name: '我发起的' }));

    expect(await screen.findByText('采购申请')).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: '搜索申请人或表单名' })).toHaveValue('报销');
    expect(router.state.location.pathname + router.state.location.search).toContain('view=process');
    expect(router.state.location.pathname + router.state.location.search).toContain('keyword=%E6%8A%A5%E9%94%80');
    expect(router.state.location.pathname + router.state.location.search).not.toContain('status=APPROVED');
  });

  it('renders started process cards with current node and instance status', async () => {
    renderTaskCenter('/tasks?view=process&keyword=采购&status=RUNNING');

    expect(await screen.findByText('采购申请')).toBeInTheDocument();
    const processCard = screen.getByRole('link', { name: /采购申请/ });
    expect(within(processCard).getByText((text) => text.includes('当前节点'))).toBeInTheDocument();
    expect(within(processCard).getByText('审批中')).toBeInTheDocument();
    expect(processCard).toHaveAttribute(
      'href',
      '/processes/9003?returnView=process&returnKeyword=%E9%87%87%E8%B4%AD&returnStatus=RUNNING',
    );
  });

  it('shows direct submissions beside workflows and opens their read-only detail', async () => {
    renderTaskCenter('/tasks?view=process');

    const card = await screen.findByRole('link', { name: /设备点检表/ });
    expect(within(card).getByText('已填报')).toBeInTheDocument();
    expect(within(card).getByText('无需审批，已完成填报')).toBeInTheDocument();
    expect(card).toHaveAttribute('href', '/submissions/7001?returnView=process');
    await userEvent.click(card);
    expect(await screen.findByRole('heading', { name: '填报详情' })).toBeInTheDocument();
  });

  it('shows empty and error states for task lists', async () => {
    setupFetch({ emptyPending: true });
    renderTaskCenter();

    expect(await screen.findByText('暂无待办任务')).toBeInTheDocument();
    vi.unstubAllGlobals();
    setupFetch({ failDone: true });
    await userEvent.click(screen.getByRole('tab', { name: '已处理' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('加载失败');
  });

  it('shows a paged-list hint when the backend reports more items', async () => {
    setupFetch({ pagedPending: true });
    renderTaskCenter();

    expect(await screen.findByText('请假申请')).toBeInTheDocument();
    expect(screen.getByText('还有更多，请继续下拉加载')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '还有更多，请继续下拉加载' }));
    expect(await screen.findByText('加班申请')).toBeInTheDocument();
  });

  it('opens a returned original form instead of a new submission', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse({ items: [{
      ...PENDING_TASKS[0],
      id: 410,
      formCode: 'leave',
      businessNo: '000000009001',
      taskType: 'REWORK',
      nodeName: '待修改原单',
    }], hasMore: false })));
    renderTaskCenter();

    const card = await screen.findByRole('link', { name: /请假申请/ });
    expect(within(card).getByText('待修改')).toBeInTheDocument();
    expect(card).toHaveAttribute('href', '/forms/leave?reworkTaskId=410');
  });
});
