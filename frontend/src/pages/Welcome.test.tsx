import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { App } from 'antd';
import { vi } from 'vitest';
import defaultSettings from '../../config/defaultSettings';
import Workplace from './Welcome';

const { requestMock, navigateMock } = vi.hoisted(() => ({
  requestMock: vi.fn(),
  navigateMock: vi.fn(),
}));

vi.mock('@umijs/max', () => ({
  Link: ({ to, children, ...props }: any) => <a href={to} {...props}>{children}</a>,
  request: requestMock,
  useNavigate: () => navigateMock,
  useModel: () => ({
    initialState: {
      currentUser: {
        displayName: '运营员',
        roles: ['operator'],
        permissions: ['page.workplace', 'workflow.instance.read', 'workflow.task.approve', 'workflow.task.reject', 'page.approval.records'],
      },
    },
  }),
}));

const overview = {
  pendingTasks: 1,
  runningInstances: 2,
  completedToday: 4,
  rejectedToday: 1,
  pendingTaskItems: [{
    taskId: 12,
    instanceId: 42,
    procInstId: 42,
    formName: '采购申请',
    applicantId: 9,
    applicantName: '林晓',
    nodeId: '部门负责人',
    status: 'PENDING',
    createdAt: '2026-08-24T09:30:00Z',
  }],
  recentInstanceItems: [{
    instanceId: 42,
    formName: '采购申请',
    startedById: 9,
    startedByName: '林晓',
    status: 'RUNNING',
    currentNodeId: '部门负责人',
    startedAt: '2026-08-24T09:30:00Z',
    updatedAt: '2026-08-24T09:30:00Z',
  }],
  statusBreakdown: { RUNNING: 2, APPROVED: 4, REJECTED: 1, WITHDRAWN: 0 },
};

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <App>
      <QueryClientProvider client={queryClient}>
        <Workplace />
      </QueryClientProvider>
    </App>,
  );
}

describe('Workplace', () => {
  beforeEach(() => {
    requestMock.mockReset();
    navigateMock.mockReset();
  });

  it('uses the mobile-aligned primary theme', () => {
    expect(defaultSettings.colorPrimary).toBe('#0b57d0');
  });

  it('renders real approval metrics and removes template dashboard copy', async () => {
    requestMock.mockResolvedValue(overview);
    renderPage();

    expect(await screen.findByText('审批运营中心')).toBeInTheDocument();
    expect(screen.getAllByText('采购申请').length).toBeGreaterThan(0);
    expect(screen.getByText('今日已完成')).toBeInTheDocument();
    expect(screen.queryByText('项目数')).not.toBeInTheDocument();
    expect(screen.queryByText('团队内排名')).not.toBeInTheDocument();
    expect(requestMock).toHaveBeenCalledWith('/api/workplace/overview');
  });

  it('explains an empty authorized scope', async () => {
    requestMock.mockResolvedValue({
      pendingTasks: 0,
      runningInstances: 0,
      completedToday: 0,
      rejectedToday: 0,
      pendingTaskItems: [],
      recentInstanceItems: [],
      statusBreakdown: { RUNNING: 0, APPROVED: 0, REJECTED: 0, WITHDRAWN: 0 },
    });
    renderPage();

    expect(await screen.findByText('当前没有待处理审批')).toBeInTheDocument();
    expect(screen.getByText('授权范围内暂无流程实例')).toBeInTheDocument();
  });

  it('disables reject for a parallel pending task', async () => {
    requestMock.mockResolvedValue({
      ...overview,
      pendingTaskItems: [{ ...overview.pendingTaskItems[0], parallelId: 'parallel' }],
    });
    renderPage();

    const reject = await screen.findByRole('button', { name: '驳回' });
    expect(reject).toBeDisabled();
    expect(reject).toHaveAttribute('title', '并行审批节点不允许驳回');
  });
});
