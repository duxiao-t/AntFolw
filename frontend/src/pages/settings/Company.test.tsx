import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App, ConfigProvider } from 'antd';
import { vi } from 'vitest';
import CompanySettingsPage from './Company';

const { requestMock, permissions } = vi.hoisted(() => ({
  requestMock: vi.fn(), permissions: { manage: true },
}));
vi.mock('@umijs/max', () => ({
  request: requestMock, useAccess: () => ({ can: () => permissions.manage }),
}));
vi.mock('@ant-design/pro-components', () => ({
  PageContainer: ({ children }: { children: React.ReactNode }) => <main>{children}</main>,
  ProTable: ({ dataSource, toolBarRender }: any) => <div>
    {toolBarRender?.()}
    {dataSource.map((row: any) => <span key={row.id}>{row.name}</span>)}
  </div>,
}));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<ConfigProvider theme={{ token: { motion: false } }}><App>
    <QueryClientProvider client={client}><CompanySettingsPage /></QueryClientProvider>
  </App></ConfigProvider>);
}

beforeEach(() => {
  permissions.manage = true;
  requestMock.mockReset();
});

it('creates a trimmed company and refreshes the real list', async () => {
  const companies = [{ id: 1, name: '已有公司' }];
  requestMock.mockImplementation((_url: string, options?: any) => {
    if (options?.method === 'POST') companies.push({ id: 2, name: options.data.name });
    return Promise.resolve([...companies]);
  });
  renderPage();
  await screen.findByText('已有公司');
  fireEvent.click(screen.getByRole('button', { name: '新建公司' }));
  const dialog = await screen.findByRole('dialog');
  await waitFor(() => expect(dialog).toBeVisible());
  fireEvent.change(within(dialog).getByLabelText('公司名称'), { target: { value: '  新公司  ' } });
  fireEvent.click(within(dialog).getByRole('button', { name: 'OK' }));
  await waitFor(() => expect(requestMock).toHaveBeenCalledWith('/api/companies', {
    method: 'POST', data: { name: '新公司' },
  }));
  expect(await screen.findByText('新公司')).toBeVisible();
});

it('shows a read only list without management permission', async () => {
  permissions.manage = false;
  requestMock.mockResolvedValue([{ id: 1, name: '本人公司' }]);
  renderPage();
  await screen.findByText('本人公司');
  expect(screen.queryByRole('button', { name: '新建公司' })).not.toBeInTheDocument();
});
