import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ConfigProvider } from 'antd';
import { vi } from 'vitest';
import { DeptPickerField } from './DeptPickerField';

const { requestMock } = vi.hoisted(() => ({ requestMock: vi.fn() }));
vi.mock('@umijs/max', () => ({ request: requestMock, useModel: () => ({}) }));

it('uses the runtime picker with the configured company and root department', async () => {
  requestMock.mockResolvedValue([
    { id: 21, companyId: 2, parentId: null, name: '其他根' },
    { id: 22, companyId: 2, parentId: null, name: '配置根' },
    { id: 23, companyId: 2, parentId: 22, name: '根下部门' },
  ]);
  const onChange = vi.fn();
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const Component = DeptPickerField.Component;
  render(<ConfigProvider theme={{ token: { motion: false } }}>
    <QueryClientProvider client={client}>
      <Component mode="runtime-fill" node={{
        id: 'department', type: 'dept_picker', props: { companyId: 2, rootDeptId: 22 },
      }} onChange={onChange} />
    </QueryClientProvider>
  </ConfigProvider>);
  await waitFor(() => expect(requestMock).toHaveBeenCalledWith('/api/pickers/departments', { params: { companyId: 2 } }));
  fireEvent.mouseDown(screen.getByRole('combobox'));
  fireEvent.click(await screen.findByText('根下部门'));
  expect(screen.queryByText('其他根')).not.toBeInTheDocument();
  expect(onChange).toHaveBeenCalledWith(23);
});
