import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import Fill from './Fill';

const { requestMock, historyPush, modelRef } = vi.hoisted(() => ({
  requestMock: vi.fn(),
  historyPush: vi.fn(),
  modelRef: { current: undefined as unknown },
}));

vi.mock('@umijs/max', () => ({
  request: requestMock,
  useParams: () => ({ code: 'leave' }),
  history: { push: historyPush },
  useModel: () => modelRef.current,
}));

const FORM = {
  code: 'leave',
  name: '请假申请',
  version: 3,
  schema: [{ id: 'reason', type: 'text', label: '请假事由', props: { required: true } }],
  settings: { workflowEnabled: true },
  process: null,
  starterFieldModes: {},
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const view = render(
    <App>
      <QueryClientProvider client={client}>
        <Fill />
      </QueryClientProvider>
    </App>,
  );
  // 运行时的字段标签只是普通 div（TextField 自己渲染的），没有 label 关联，按 data-field-id 取输入框。
  return {
    ...view,
    async typeReason() {
      const input = await waitFor(() => {
        const found = view.container.querySelector('[data-field-id="reason"] input');
        if (!found) throw new Error('reason input missing');
        return found as HTMLInputElement;
      });
      fireEvent.change(input, { target: { value: '回家探亲' } });
    },
  };
}

function startPosts() {
  return requestMock.mock.calls.filter(
    ([target, options]) =>
      target === '/api/mobile/instances' &&
      (options as { method?: string } | undefined)?.method === 'POST',
  );
}

function startBody() {
  const first = startPosts()[0];
  if (!first) throw new Error('start call missing');
  return (first[1] as { data: Record<string, unknown> }).data;
}

function keyOf(call: unknown[] | undefined) {
  if (!call) throw new Error('start call missing');
  const options = call[1] as { headers?: Record<string, string> } | undefined;
  return options?.headers?.['Idempotency-Key'];
}

