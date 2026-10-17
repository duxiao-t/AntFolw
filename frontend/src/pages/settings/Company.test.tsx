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
// ProTable 只保留本页用到的三块能力：工具栏、数据行、操作列。
vi.mock('@ant-design/pro-components', () => ({
  PageContainer: ({ children }: { children: React.ReactNode }) => <main>{children}</main>,
  ProTable: ({ dataSource, toolBarRender, columns }: any) => <div>
    {toolBarRender?.()}
    {dataSource.map((row: any) => <div key={row.id}>
      <span>{row.name}</span>
      {(columns ?? [])
        .filter((column: any) => column.dataIndex === 'actions')
        .map((column: any) => <span key="actions">{column.render?.(null, row, 0, null)}</span>)}
    </div>)}
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
  expect(screen.queryByRole('button', { name: '编辑' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '停用' })).not.toBeInTheDocument();
});

it('edits a company name through PUT and refreshes the list', async () => {
  requestMock.mockImplementation((url: string, options?: any) => {
    if (options?.method === 'PUT') return Promise.resolve({ id: 1, name: options.data.name });
    return Promise.resolve([{ id: 1, name: '旧名' }]);
  });
  renderPage();
  await screen.findByText('旧名');
  fireEvent.click(screen.getByRole('button', { name: '编辑' }));
  const dialog = await screen.findByRole('dialog');
  await waitFor(() => expect(dialog).toBeVisible());
  expect(within(dialog).getByLabelText('公司名称')).toHaveValue('旧名');
  fireEvent.change(within(dialog).getByLabelText('公司名称'), { target: { value: ' 新名 ' } });
  fireEvent.click(within(dialog).getByRole('button', { name: 'OK' }));
  await waitFor(() => expect(requestMock).toHaveBeenCalledWith('/api/companies/1', {
    method: 'PUT', data: { name: '新名' },
  }));
});

it('loads the company detail from the server when viewing', async () => {
  requestMock.mockImplementation((url: string) =>
    Promise.resolve(url === '/api/companies/1'
      ? { id: 1, name: '详情公司', createdAt: '2026-10-01T00:00:00Z' }
      : [{ id: 1, name: '列表公司' }]));
  renderPage();
  await screen.findByText('列表公司');
  fireEvent.click(screen.getByRole('button', { name: '查看' }));
  await waitFor(() => expect(requestMock).toHaveBeenCalledWith('/api/companies/1'));
  const dialog = await screen.findByRole('dialog');
  expect(await within(dialog).findByText('详情公司')).toBeVisible();
});

it('deactivates a company only after the confirmation is accepted', async () => {
  requestMock.mockImplementation((_url: string, options?: any) =>
    Promise.resolve(options?.method === 'DELETE' ? undefined : [{ id: 1, name: '待停用公司' }]));
  renderPage();
  await screen.findByText('待停用公司');
  fireEvent.click(screen.getByRole('button', { name: '停用' }));
  // Popconfirm 的确认按钮在 portal 里，按容器类名取主按钮（文案「停用」）。
  await waitFor(() => expect(document.querySelector('.ant-popconfirm-buttons')).toBeTruthy());
  const confirmButtons = document.querySelectorAll('.ant-popconfirm-buttons .ant-btn');
  fireEvent.click(confirmButtons[confirmButtons.length - 1]);
  await waitFor(() => expect(requestMock).toHaveBeenCalledWith('/api/companies/1', { method: 'DELETE' }));
});
