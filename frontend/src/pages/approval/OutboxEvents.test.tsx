import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { vi } from 'vitest';
import OutboxEvents from './OutboxEvents';

const { requestMock, canMock } = vi.hoisted(() => ({ requestMock: vi.fn(), canMock: vi.fn() }));
vi.mock('@umijs/max', () => ({ request: requestMock, useAccess: () => ({ can: canMock }) }));

function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><OutboxEvents /></QueryClientProvider>);
}

beforeEach(() => {
  requestMock.mockReset();
  requestMock.mockResolvedValue({ records: [{ id: 'event-uuid', instanceId: 5,
    eventType: 'TASK_ASSIGNED', attempts: 10, error: '投递失败，请查看受限服务日志' }], total: 1 });
});

it('replays one failed event with its original id and refreshes the list', async () => {
  canMock.mockReturnValue(true);
  mount();
  fireEvent.click(await screen.findByRole('button', { name: '重新投递' }));
  await waitFor(() => expect(requestMock).toHaveBeenCalledWith(
    '/api/workflow-monitor/outbox/event-uuid/retry', { method: 'POST' }));
  await waitFor(() => expect(requestMock.mock.calls.filter(
    ([path]) => String(path).includes('status=DEAD'))).toHaveLength(2));
});

it('keeps the failed message list visible without offering unauthorized replay', async () => {
  canMock.mockReturnValue(false);
  mount();
  expect(await screen.findByText('TASK_ASSIGNED')).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '重新投递' })).not.toBeInTheDocument();
});
