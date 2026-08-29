import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { vi } from 'vitest';
import WecomPage, { syncPollInterval } from './Wecom';

const { requestMock } = vi.hoisted(() => ({ requestMock: vi.fn() }));

vi.mock('@umijs/max', () => ({ request: requestMock }));
vi.mock('@ant-design/pro-components', () => ({
  PageContainer: ({ children }: { children: React.ReactNode }) => <main>{children}</main>,
}));

const settings = {
  companyId: 1,
  corpId: 'ww-corp',
  secretConfigured: true,
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <App>
      <QueryClientProvider client={client}>
        <WecomPage />
      </QueryClientProvider>
    </App>,
  );
}

describe('WecomPage', () => {
  beforeEach(() => requestMock.mockReset());

  it('restores saved configuration without exposing the secret and shows partial results', async () => {
    const latestJob = {
      id: 9,
      companyId: 1,
      status: 'PARTIAL',
      phase: 'COMPLETED',
      percent: 100,
      totalUsers: 10,
      processedUsers: 10,
      createdUsers: 3,
      updatedUsers: 6,
      failedUsers: 1,
      message: '部分数据未能同步',
      errorSummary: ['成员 u***1 的手机号重复'],
      finishedAt: '2026-08-29T01:00:00Z',
    };
    requestMock.mockImplementation((url: string) => {
      if (url === '/api/companies') return Promise.resolve([{ id: 1, name: 'AntFlow' }]);
      if (url === '/api/integrations/wecom/settings') return Promise.resolve({
        ...settings,
        latestJob,
      });
      if (url === '/api/integrations/wecom/sync-jobs/9') return Promise.resolve(latestJob);
      return Promise.resolve({});
    });

    renderPage();

    expect(await screen.findByDisplayValue('ww-corp')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('已配置（留空不修改）')).toHaveValue('');
    expect(screen.getByText('10/10')).toBeInTheDocument();
    expect(screen.getByText('成员 u***1 的手机号重复')).toBeInTheDocument();
    expect(screen.queryByDisplayValue(/secret/i)).not.toBeInTheDocument();
  });

  it('blocks sync while edits are unsaved, preserves a blank secret, then starts a job', async () => {
    requestMock.mockImplementation((url: string, options?: { method?: string; data?: unknown }) => {
      if (url === '/api/companies') return Promise.resolve([{ id: 1, name: 'AntFlow' }]);
      if (url === '/api/integrations/wecom/settings' && options?.method === 'PUT') {
        return Promise.resolve({ ...settings, corpId: 'ww-updated' });
      }
      if (url === '/api/integrations/wecom/settings') return Promise.resolve(settings);
      if (url === '/api/integrations/wecom/sync-jobs') return Promise.resolve({
        id: 10,
        companyId: 1,
        status: 'PENDING',
        phase: 'CONNECTING',
        percent: 0,
        totalUsers: 0,
        processedUsers: 0,
        createdUsers: 0,
        updatedUsers: 0,
        failedUsers: 0,
        errorSummary: [],
      });
      if (url === '/api/integrations/wecom/sync-jobs/10') return Promise.resolve({
        id: 10, status: 'PENDING', phase: 'CONNECTING', percent: 0, errorSummary: [],
      });
      return Promise.resolve({});
    });

    renderPage();
    const corpId = await screen.findByDisplayValue('ww-corp');
    fireEvent.change(corpId, { target: { value: 'ww-updated' } });
    expect(screen.getByRole('button', { name: /开始同步/ })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: '保存连接配置' }));
    await waitFor(() => expect(requestMock).toHaveBeenCalledWith(
      '/api/integrations/wecom/settings',
      expect.objectContaining({
        method: 'PUT',
        data: { companyId: 1, corpId: 'ww-updated' },
      }),
    ));
    await waitFor(() => expect(screen.getByRole('button', { name: /开始同步/ })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: /开始同步/ }));
    await waitFor(() => expect(requestMock).toHaveBeenCalledWith(
      '/api/integrations/wecom/sync-jobs',
      { method: 'POST', data: { companyId: 1 } },
    ));
  });

  it('polls only active tasks', () => {
    expect(syncPollInterval({ status: 'RUNNING' } as never)).toBe(1000);
    expect(syncPollInterval({ status: 'PENDING' } as never)).toBe(1000);
    expect(syncPollInterval({ status: 'SUCCESS' } as never)).toBe(false);
    expect(syncPollInterval({ status: 'FAILED' } as never)).toBe(false);
  });
});
