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

describe('runtime Fill company attribution', () => {
  beforeEach(() => {
    requestMock.mockReset();
    historyPush.mockReset();
    requestMock.mockImplementation((url: string) => {
      if (url.includes('/api/mobile/forms/')) return Promise.resolve(FORM);
      if (url === '/api/companies') return Promise.resolve([{ id: 1, name: 'AntFlow' }]);
      if (url === '/api/mobile/instances') return Promise.resolve({ instanceId: 9 });
      if (url === '/api/forms/data') return Promise.resolve({ dataId: 9 });
      return Promise.resolve(null);
    });
  });

  it('leaves the company to the server for an account that has a department', async () => {
    modelRef.current = { initialState: { currentUser: { id: 7, departmentId: 20 } } };
    const page = renderPage();

    await page.typeReason();
    expect(screen.queryByText(/请选择本次提交归属的公司/)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '提交并发起审批' }));

    await waitFor(() => expect(startPosts()).toHaveLength(1));
    expect(startBody().companyId).toBeNull();
  });

  it('requires a department-less account to pick a company before it can submit', async () => {
    // 种子 admin 没有部门（/api/auth/me 的 departmentId 是 ""）：服务端推不出归属，必须显式选（P4a）。
    modelRef.current = { initialState: { currentUser: { id: 1, departmentId: '' } } };
    const page = renderPage();

    await page.typeReason();
    const submit = screen.getByRole('button', { name: '提交并发起审批' });
    expect(screen.getByText(/请选择本次提交归属的公司/)).toBeInTheDocument();
    expect(submit).toBeDisabled();

    fireEvent.click(submit);
    expect(startPosts()).toHaveLength(0);

    fireEvent.mouseDown(screen.getByRole('combobox'));
    fireEvent.click(await screen.findByText('AntFlow'));
    fireEvent.click(submit);

    await waitFor(() => expect(startPosts()).toHaveLength(1));
    expect(startBody().companyId).toBe(1);
  });

  it('reuses the same idempotency key on retry and rotates it after success', async () => {
    modelRef.current = { initialState: { currentUser: { id: 7, departmentId: 20 } } };
    let attempts = 0;
    requestMock.mockImplementation((url: string) => {
      if (url.includes('/api/mobile/forms/')) return Promise.resolve(FORM);
      if (url === '/api/mobile/instances') {
        attempts += 1;
        // 第一次当作"客户端超时"：服务端可能已经落库了，用户重按必须带同一个 key，
        // 否则就是两份实例（回归 A2：原来每次调用都现生成 key，等于没有幂等）。
        return attempts === 1
          ? Promise.reject(new Error('network timeout'))
          : Promise.resolve({ instanceId: 9 });
      }
      return Promise.resolve(null);
    });

    const page = renderPage();
    await page.typeReason();
    const submit = screen.getByRole('button', { name: '提交并发起审批' });

    fireEvent.click(submit);
    await waitFor(() => expect(startPosts()).toHaveLength(1));
    fireEvent.click(submit);
    await waitFor(() => expect(startPosts()).toHaveLength(2));

    const firstKey = keyOf(startPosts()[0]);
    expect(firstKey).toBeTruthy();
    expect(keyOf(startPosts()[1])).toBe(firstKey);

    // 成功后换新键：下一次提交是新操作，不能被上一次的响应重放掉。
    await waitFor(() => expect(historyPush).toHaveBeenCalled());
    fireEvent.click(submit);
    await waitFor(() => expect(startPosts()).toHaveLength(3));
    expect(keyOf(startPosts()[2])).not.toBe(firstKey);
  });
});
