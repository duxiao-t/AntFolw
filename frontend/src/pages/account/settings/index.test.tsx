import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App, ConfigProvider } from 'antd';
import { vi } from 'vitest';
import AccountSettings from './index';

const { requestMock } = vi.hoisted(() => ({ requestMock: vi.fn() }));
vi.mock('@umijs/max', () => ({ request: requestMock }));
// PageContainer 内部会拉 antd 的 calendar locale，测试环境解析不到（Cannot find module
// 'antd/es/calendar/locale/zh_CN'）。其他页面测试也这样把它换成一个纯容器。
vi.mock('@ant-design/pro-components', () => ({
  PageContainer: ({ children }: { children: React.ReactNode }) => <main>{children}</main>,
}));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <ConfigProvider theme={{ token: { motion: false } }}>
      <App>
        <QueryClientProvider client={client}>
          <AccountSettings />
        </QueryClientProvider>
      </App>
    </ConfigProvider>,
  );
}

beforeEach(() => {
  requestMock.mockReset();
  requestMock.mockImplementation((url: string) => {
    if (url === '/api/account/profile') {
      // roles 是必填数组：页面直接读 profile.data.roles.length，缺了会整页崩。
      return Promise.resolve({
        id: 1,
        username: 'admin',
        displayName: '管理员',
        employeeNo: '000001',
        departmentName: '技术部',
        position: null,
        email: null,
        phone: null,
        roles: ['admin'],
      });
    }
    return Promise.resolve(undefined);
  });
});

/**
 * 回归护栏：请求体必须只有后端 DTO 认识的两个字段。
 * 早先直接把 antd 表单值整体发出去，多出的 confirmPassword 让后端 Jackson 反序列化失败 → 500。
 */
it('改密只提交 currentPassword 与 newPassword', async () => {
  const { container } = renderPage();
  await screen.findByRole('button', { name: '修改密码' });

  // 三个密码框按渲染顺序就是：原密码 / 新密码 / 确认新密码。
  const inputs = container.querySelectorAll('input[type="password"]');
  expect(inputs).toHaveLength(3);
  fireEvent.change(inputs[0], { target: { value: 'old-pass' } });
  fireEvent.change(inputs[1], { target: { value: 'new-pass-123' } });
  fireEvent.change(inputs[2], { target: { value: 'new-pass-123' } });
  fireEvent.click(screen.getByRole('button', { name: '修改密码' }));

  await waitFor(() => {
    const call = requestMock.mock.calls.find(([url]) => url === '/api/account/password');
    expect(call).toBeTruthy();
    expect(call?.[1]?.data).toEqual({
      currentPassword: 'old-pass',
      newPassword: 'new-pass-123',
    });
  });
});

/** 两次输入不一致时不该发请求（本地校验拦下）。 */
it('确认密码不一致时不发请求', async () => {
  const { container } = renderPage();
  await screen.findByRole('button', { name: '修改密码' });

  const inputs = container.querySelectorAll('input[type="password"]');
  fireEvent.change(inputs[0], { target: { value: 'old-pass' } });
  fireEvent.change(inputs[1], { target: { value: 'new-pass-123' } });
  fireEvent.change(inputs[2], { target: { value: 'new-pass-456' } });
  fireEvent.click(screen.getByRole('button', { name: '修改密码' }));

  await screen.findByText('两次输入的密码不一致');
  expect(requestMock.mock.calls.some(([url]) => url === '/api/account/password')).toBe(false);
});
