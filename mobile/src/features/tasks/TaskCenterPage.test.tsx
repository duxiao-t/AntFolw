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
    id: 9003,
    status: 'RUNNING',
    formName: '采购申请',
    currentNodeName: '部门审批',
    startedAt: '2026-07-19T11:00:00+08:00',
    finishedAt: null,
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
      if (url.pathname === '/api/mobile/instances') {
        expect(url.searchParams.get('page')).toBe('1');
        expect(url.searchParams.get('size')).toBe('20');
        expect(url.searchParams.has('keyword')).toBe(true);
        return jsonResponse({ items: STARTED_PROCESSES, hasMore: false });
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

    expect(await screen.findByRole('heading', { name: '任务中心' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '待办' })).toHaveAttribute('aria-selected', 'true');
    expect(await screen.findByText('请假申请')).toBeInTheDocument();
    const pendingCard = screen.getByRole('link', { name: /请假申请/ });
    expect(screen.getByText('张三 · 研发部')).toBeInTheDocument();
    expect(within(pendingCard).getByText('待审批')).toBeInTheDocument();
    expect(within(pendingCard).getByText('进行中')).toBeInTheDocument();
    expect(pendingCard).toHaveAttribute(
      'href',
      '/tasks/401?returnView=pending',
    );
    await userEvent.click(pendingCard);
    expect(await screen.findByRole('heading', { name: '任务详情' })).toBeInTheDocument();
  });

  it('restores done view and filters from URL, then keeps filters when switching tabs', async () => {
    const { router } = renderTaskCenter('/tasks?view=done&keyword=报销&status=SKIPPED');

    expect(await screen.findByText('报销申请')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '已处理' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('searchbox', { name: '搜索任务' })).toHaveValue('报销');
    expect(screen.getByLabelText('状态筛选')).toHaveValue('SKIPPED');
    const doneCard = screen.getByRole('link', { name: /报销申请/ });
    expect(within(doneCard).getByText('已同意')).toBeInTheDocument();
    expect(within(doneCard).getByText('已通过')).toBeInTheDocument();
    expect(doneCard).toHaveAttribute(
      'href',
      '/tasks/402?returnView=done&returnKeyword=%E6%8A%A5%E9%94%80&returnStatus=SKIPPED',
    );

    await userEvent.click(screen.getByRole('tab', { name: '我发起的' }));

    expect(await screen.findByText('采购申请')).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: '搜索任务' })).toHaveValue('报销');
    expect(router.state.location.pathname + router.state.location.search).toContain('view=process');
    expect(router.state.location.pathname + router.state.location.search).toContain('keyword=%E6%8A%A5%E9%94%80');
    expect(router.state.location.pathname + router.state.location.search).not.toContain('status=SKIPPED');
  });

  it('renders started process cards with current node and instance status', async () => {
    renderTaskCenter('/tasks?view=process&keyword=采购&status=RUNNING');

    expect(await screen.findByText('采购申请')).toBeInTheDocument();
    const processCard = screen.getByRole('link', { name: /采购申请/ });
    expect(within(processCard).getByText('当前节点：部门审批')).toBeInTheDocument();
    expect(within(processCard).getByText('进行中')).toBeInTheDocument();
    expect(processCard).toHaveAttribute(
      'href',
      '/processes/9003?returnView=process&returnKeyword=%E9%87%87%E8%B4%AD&returnStatus=RUNNING',
    );
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
    expect(screen.getByText('还有更多任务，请继续下拉加载')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '加载更多' }));
    expect(await screen.findByText('加班申请')).toBeInTheDocument();
  });
});
