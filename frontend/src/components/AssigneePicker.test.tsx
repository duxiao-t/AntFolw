import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AssigneePicker } from './AssigneePicker';

const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@umijs/max', () => ({ request }));
vi.mock('antd', () => ({
  Spin: () => null,
  Select: ({ value, options, onSearch }: {
    value: number[]; options: Array<{ value: number; label: string }>;
    onSearch: (value: string) => void;
  }) => <div>
    <input aria-label="搜索候选项" onChange={(event) => onSearch(event.target.value)} />
    {value.map((id) => <span key={id}>{options.find((option) => option.value === id)?.label ?? id}</span>)}
    {options.map((option) => <span key={option.value}>{option.label}</span>)}
  </div>,
}));

describe('AssigneePicker', () => {
  it('searches beyond the first page of roles and resolves already-selected names', async () => {
    request.mockImplementation((url: string, options?: { params?: { keyword?: string } }) => {
      if (url === '/api/pickers/roles/selected') return Promise.resolve([
        { id: 99, code: 'AUDIT', name: '审计角色' },
      ]);
      if (url === '/api/pickers/roles') return Promise.resolve(options?.params?.keyword
        ? [{ id: 51, code: 'FINANCE', name: '财务审批' }] : []);
      throw new Error(`Unexpected request: ${url}`);
    });

    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AssigneePicker mode="role" value={[99]} onChange={vi.fn()} />
    </QueryClientProvider>);

    await waitFor(() => expect(screen.getAllByText('审计角色 (AUDIT)').length).toBeGreaterThan(0));
    fireEvent.change(screen.getByRole('textbox', { name: '搜索候选项' }), { target: { value: '财务' } });
    await waitFor(() => expect(request).toHaveBeenCalledWith('/api/pickers/roles', {
      params: { keyword: '财务' },
    }));
    expect(screen.getAllByText('审计角色 (AUDIT)').length).toBeGreaterThan(0);
  });

  it('用户候选用显示名 + 工号，不依赖已不再下发的登录账号', async () => {
    request.mockImplementation((url: string) => {
      if (url === '/api/pickers/users/selected') return Promise.resolve([
        { id: 7, displayName: '张三', employeeNo: '000007', department: '研发部' },
      ]);
      // 列表端点返回的是窄 DTO：只有 id/displayName/department/employeeNo，没有 username
      if (url === '/api/pickers/users') return Promise.resolve([
        { id: 8, displayName: '李四', employeeNo: '000008', department: '财务部' },
      ]);
      throw new Error(`Unexpected request: ${url}`);
    });

    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <AssigneePicker mode="user" value={[7]} onChange={vi.fn()} />
    </QueryClientProvider>);

    await waitFor(() => expect(screen.getAllByText('张三 (000007) · 研发部').length)
      .toBeGreaterThan(0));
    expect(await screen.findByText('李四 (000008) · 财务部')).toBeInTheDocument();
    expect(screen.queryByText(/undefined/)).not.toBeInTheDocument();
  });
});
