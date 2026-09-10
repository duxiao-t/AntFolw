import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { vi } from 'vitest';
import WorkflowMonitor from './WorkflowMonitor';

const { requestMock } = vi.hoisted(() => ({ requestMock: vi.fn() }));
vi.mock('@umijs/max', () => ({ request: requestMock }));

describe('WorkflowMonitor', () => {
  it('renders stuck, overdue, rejection and outbox data', async () => {
    requestMock.mockResolvedValue({
      stuckInstances: [{ id: 7, current_node_id: 'manager', started_at: '2026-08-30T09:00:00Z' }],
      overdueTasks: [{ task_id: 8, instance_id: 7, node_id: 'manager', assignee_id: 2, timeout_at: '2026-08-30T10:00:00Z' }],
      nodeRejectionRates: [{ node_id: 'finance', rejected: 3, decided: 10, reject_rate: 30 }],
      fallbackBacklogs: [{ assignee_id: 2, assignee_name: '李经理', pending_count: 4, affected_node_count: 2, affected_instance_count: 3, oldest_pending_at: '2026-08-30T08:00:00Z' }],
      outbox: { pending: 2, dead: 1, oldest_pending: '2026-08-30T08:00:00Z' },
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><WorkflowMonitor /></QueryClientProvider>);

    expect(await screen.findByText('流程运行监控')).toBeInTheDocument();
    expect(screen.getByText('#7')).toBeInTheDocument();
    expect(screen.getByText('#8')).toBeInTheDocument();
    expect(screen.getByText('30.00%')).toBeInTheDocument();
    expect(screen.getByText('兜底任务堆积')).toBeInTheDocument();
    expect(screen.getByText('李经理')).toBeInTheDocument();
    expect(requestMock).toHaveBeenCalledWith('/api/workflow-monitor?limit=50');
  });
});
