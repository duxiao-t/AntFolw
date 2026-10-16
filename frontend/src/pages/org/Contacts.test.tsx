import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App, ConfigProvider } from 'antd';
import { vi } from 'vitest';
import ContactsPage from './Contacts';

const { requestMock } = vi.hoisted(() => ({ requestMock: vi.fn() }));
vi.mock('@umijs/max', () => ({
  request: requestMock,
  useAccess: () => ({ canAdmin: true, canOrgDepartmentManage: true }),
}));
vi.mock('@ant-design/pro-components', () => ({
  PageContainer: ({ children }: { children: React.ReactNode }) => <main>{children}</main>,
  ProTable: ({ dataSource, columns, toolBarRender }: any) => (
    <div>
      {toolBarRender?.()}
      {dataSource.map((row: any) => <div key={row.id}>
        {columns.map((column: any) => <span key={column.key ?? column.dataIndex}>
          {column.render ? column.render(row[column.dataIndex], row) : row[column.dataIndex]}
        </span>)}
      </div>)}
    </div>
  ),
}));

const departments = (companyId: number) => [{
  id: companyId * 10, companyId, parentId: null, name: `部门${companyId}`,
  canManageUsers: true, canManageDepartment: true, canReadMembers: true,
}];
const member = {
  id: 100, displayName: '成员甲', username: 'member', employeeNo: '100', deptId: 10,
  managerId: 101, managerDisplayName: '原上级', status: 'ACTIVE',
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<ConfigProvider theme={{ token: { motion: false } }}><App>
    <QueryClientProvider client={client}><ContactsPage /></QueryClientProvider>
  </App></ConfigProvider>);
}

async function choose(label: string, option: string) {
  fireEvent.mouseDown(screen.getByRole('combobox', { name: label }));
  fireEvent.click(await screen.findByText(option, {
    selector: '.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option-content',
  }));
}

beforeEach(() => {
  requestMock.mockReset();
  requestMock.mockImplementation((url: string, options?: any) => {
    if (url === '/api/companies') return Promise.resolve([{ id: 1, name: '甲公司' }, { id: 2, name: '乙公司' }]);
    if (url.startsWith('/api/departments')) {
      const companyId = options?.params?.companyId ?? Number(new URL(url, 'http://local').searchParams.get('companyId'));
      return Promise.resolve(departments(companyId));
    }
    if (url === '/api/users/page') return Promise.resolve({ records: [member], total: 1 });
    if (url === '/api/users/manager-candidates') return Promise.resolve([]);
    return Promise.resolve({});
  });
});

it('requires explicit company and resets search, department and editor on company switch', async () => {
  renderPage();
  await screen.findByText('请先选择公司');
  expect(requestMock.mock.calls.some(([url]) => url.startsWith('/api/departments'))).toBe(false);
  await choose('通讯录公司', '甲公司');
  fireEvent.click(await screen.findByText('部门1'));
  fireEvent.click(await screen.findByRole('button', { name: /编辑/ }));
  await waitFor(() => expect(screen.getByRole('dialog')).toBeVisible());
  await choose('通讯录公司', '乙公司');
  await screen.findByText('部门2');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.getByPlaceholderText('搜索部门或人员')).toHaveValue('');
  fireEvent.change(screen.getByPlaceholderText('搜索部门或人员'), { target: { value: '同名' } });
  await waitFor(() => expect(requestMock).toHaveBeenCalledWith('/api/users/page', {
    params: { companyId: 2, page: 1, size: 15, keyword: '同名' },
  }));
});

it('auto selects a sole company and preserves failed transfer input with explicit null manager', async () => {
  const implementation = requestMock.getMockImplementation();
  requestMock.mockImplementation((url: string, options?: any) => {
    if (url === '/api/companies') return Promise.resolve([{ id: 1, name: '甲公司' }]);
    if (url === '/api/users/100' && options?.method === 'PUT') return Promise.reject(new Error('调岗失败'));
    return implementation?.(url, options);
  });
  renderPage();
  fireEvent.click(await screen.findByText('部门1'));
  fireEvent.click(await screen.findByRole('button', { name: /编辑/ }));
  const dialog = await screen.findByRole('dialog');
  await waitFor(() => expect(dialog).toBeVisible());
  fireEvent.change(within(dialog).getByLabelText('姓名'), { target: { value: '保留输入' } });
  const managerSelect = within(dialog).getByLabelText('直属上级').closest('.ant-select');
  const clearManager = managerSelect?.querySelector('.ant-select-clear');
  if (!clearManager) throw new Error('缺少清空上级控件');
  fireEvent.mouseDown(clearManager);
  fireEvent.click(within(dialog).getByRole('button', { name: 'OK' }));
  await waitFor(() => expect(requestMock).toHaveBeenCalledWith('/api/users/100', expect.objectContaining({
    method: 'PUT', data: expect.objectContaining({ displayName: '保留输入', deptId: 10, managerId: null }),
  })));
  expect(within(dialog).getByLabelText('姓名')).toHaveValue('保留输入');
  expect(dialog).toBeVisible();
});

it('selects target department for a cross company transfer and refreshes manager candidates', async () => {
  renderPage();
  await choose('通讯录公司', '甲公司');
  fireEvent.click(await screen.findByText('部门1'));
  fireEvent.click(await screen.findByRole('button', { name: /编辑/ }));
  const dialog = await screen.findByRole('dialog');
  await choose('所属公司', '乙公司');
  fireEvent.click(within(dialog).getByRole('button', { name: 'OK' }));
  await screen.findByText('请选择所属部门', { selector: '.ant-form-item-explain-error' });
  fireEvent.mouseDown(within(dialog).getByLabelText('所属部门'));
  fireEvent.click(await screen.findByText('部门2', { selector: '.ant-select-tree-title' }));
  await waitFor(() => expect(requestMock).toHaveBeenCalledWith('/api/users/manager-candidates', {
    params: { deptId: 20, excludeUserId: 100, keyword: undefined },
  }));
  fireEvent.click(within(dialog).getByRole('button', { name: 'OK' }));
  await waitFor(() => expect(requestMock).toHaveBeenCalledWith('/api/users/100', expect.objectContaining({
    method: 'PUT', data: expect.objectContaining({ deptId: 20, managerId: 101 }),
  })));
});
