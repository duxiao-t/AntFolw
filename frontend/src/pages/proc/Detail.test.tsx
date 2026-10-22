import { App } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import DetailPage from './Detail';

// 只挡边界：路由/请求/当前用户来自 Umi 运行时，表单渲染器会拖进整棵字段树。
const { request, back, params, initialState } = vi.hoisted(() => ({
  request: vi.fn(),
  back: vi.fn(),
  params: { id: '1' } as Record<string, string | undefined>,
  initialState: { currentUser: null as any },
}));

vi.mock('@umijs/max', () => ({
  request,
  history: { back },
  useParams: () => params,
  useModel: () => ({ initialState }),
}));

vi.mock('../../components/FormRenderer/FormRenderer', () => ({
  FormRenderer: () => <div data-testid="form-renderer" />,
}));

const PENDING_TASK = {
  id: 100,
  nodeId: 'approval',
  taskType: 'APPROVAL',
  status: 'PENDING',
  assigneeId: 1,
};

type StubOptions = {
  startedBy?: number;
  status?: string;
  currentNodeId?: string;
  visibility?: string;
  tasks?: any[];
};

/**
 * 详情页的前两个请求是 /api/instances/:id 与 /api/mobile/instances/:id，
 * 分别喂 runtime 与 presentation。未知端点在测试里应当报错，而不是静默返回 {}。
 */
function stubApi(options: StubOptions = {}) {
  const instance = {
    id: 1,
    status: options.status ?? 'RUNNING',
    startedBy: options.startedBy ?? 1,
    currentNodeId: options.currentNodeId ?? 'approval',
    startedAt: '2026-10-01T09:00:00+08:00',
    finishedAt: null,
  };
  const visibility = options.visibility ?? 'FULL';
  const tasks = options.tasks ?? [];

  request.mockImplementation(async (url: string) => {
    if (url === '/api/instances/1') {
      return {
        instance,
        tasks,
        schema: [],
        formData: {},
        nodeInstances: [],
        history: [],
        visibility,
      };
    }
    if (url === '/api/mobile/instances/1') {
      return {
        visibility,
        id: 1,
        status: instance.status,
        formName: '请假申请',
        businessNo: 'QJ-2026-0001',
        applicantName: '张三',
        currentNodeName: '部门审批',
      };
    }
    if (url === '/api/pickers/users/selected') return [];
    if (url.endsWith('/comment-presets')) return { approve: [], reject: [] };
    throw new Error(`Unexpected request: ${url}`);
  });
}

function renderDetail() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <App>
      <QueryClientProvider client={client}>
        <DetailPage />
      </QueryClientProvider>
    </App>,
  );
}

describe('流程详情页', () => {
  beforeEach(() => {
    request.mockReset();
    back.mockReset();
    params.id = '1';
    initialState.currentUser = { id: 1, roles: ['admin'] };
  });

  it('详情还在拉取时显示加载占位，不提前渲染头部', () => {
    // 永不 resolve：页面停在 pending 态。Skeleton 自身没有可访问角色/名称，
    // 这里只能按页面自己的加载容器断言，再确认没有落到错误态。
    request.mockReturnValue(new Promise(() => {}));
    const { container } = renderDetail();

    expect(container.querySelector('.process-detail-loading')).not.toBeNull();
    expect(screen.queryByText('流程详情加载失败')).toBeNull();
    expect(screen.queryByRole('heading', { name: '请假申请' })).toBeNull();
  });

  it('请求失败时显示错误提示，点重新加载能恢复', async () => {
    request.mockRejectedValue(new Error('network down'));
    renderDetail();

    expect(await screen.findByText('流程详情加载失败')).toBeInTheDocument();
    expect(screen.getByText('请检查网络连接后重新加载。')).toBeInTheDocument();
    const retry = screen.getByRole('button', { name: '重新加载' });
    expect(retry).toBeEnabled();

    // 网络恢复后再点一次：错误态必须能被真实 refetch 取代。
    stubApi();
    fireEvent.click(retry);
    expect(await screen.findByRole('heading', { name: '请假申请' })).toBeInTheDocument();
  });

  it('有待办任务时显示同意/驳回', async () => {
    stubApi({ tasks: [PENDING_TASK] });
    renderDetail();

    expect(await screen.findByRole('button', { name: /^同\s*意$/ })).toBeEnabled();
    expect(screen.getByRole('button', { name: /^驳\s*回$/ })).toBeEnabled();
  });

  it('没有指派给当前用户的待办时不显示同意/驳回', async () => {
    stubApi({ tasks: [{ ...PENDING_TASK, status: 'COMPLETED', assigneeId: 2 }] });
    renderDetail();

    // 等头部出来，确认渲染的不是加载/错误态。
    await screen.findByRole('heading', { name: '请假申请' });
    expect(screen.queryByRole('button', { name: /^同\s*意$/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /^驳\s*回$/ })).toBeNull();
  });

  it('并行分支的待办禁止驳回，并说明原因', async () => {
    stubApi({ tasks: [{ ...PENDING_TASK, parallelId: 'parallel-1' }] });
    renderDetail();

    const reject = await screen.findByRole('button', { name: /^驳\s*回$/ });
    expect(reject).toBeDisabled();
    expect(reject).toHaveAttribute('title', '并行审批节点不允许驳回');
    expect(screen.getByRole('button', { name: /^同\s*意$/ })).toBeEnabled();
  });

  const withdrawCases: Array<{
    name: string;
    options: StubOptions;
    visible: boolean;
    notice?: string;
  }> = [
    { name: '发起人 + 审批中 + 完整可见', options: { startedBy: 1 }, visible: true },
    { name: '非发起人', options: { startedBy: 2 }, visible: false },
    {
      name: '流程处于待修改',
      options: { startedBy: 1, currentNodeId: '__rework__' },
      visible: false,
      notice: '流程待修改',
    },
    {
      name: '当前账号只能看摘要',
      options: { startedBy: 1, visibility: 'SUMMARY' },
      visible: false,
      notice: '当前账号仅可查看流程摘要',
    },
  ];

  it.each(withdrawCases)('撤回按钮可见性：$name', async ({ options, visible, notice }) => {
    stubApi(options);
    renderDetail();

    await screen.findByRole('heading', { name: '请假申请' });
    const withdraw = screen.queryByRole('button', { name: '撤回流程' });
    if (visible) expect(withdraw).toBeEnabled();
    else expect(withdraw).toBeNull();
    if (notice) expect(screen.getByText(notice)).toBeInTheDocument();
  });

  it('同意弹窗提交到该待办任务，并带上填写的意见', async () => {
    stubApi({ tasks: [PENDING_TASK] });
    renderDetail();

    fireEvent.click(await screen.findByRole('button', { name: /^同\s*意$/ }));
    expect(await screen.findByText('同意审批')).toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText('审批意见（可选）'), {
      target: { value: '  材料齐全，同意  ' },
    });
    fireEvent.click(screen.getByRole('button', { name: '确认同意' }));

    await waitFor(() => expect(request).toHaveBeenCalledWith('/api/tasks/100/approve', {
      method: 'POST',
      data: { comment: '材料齐全，同意' },
    }));
  });
});
