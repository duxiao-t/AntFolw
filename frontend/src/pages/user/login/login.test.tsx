// @ts-ignore
import { startMock } from '@@/requestRecordMock';
import { TestBrowser } from '@@/testBrowser';
import { fireEvent, render } from '@testing-library/react';
import React, { act } from 'react';

let server: { close: () => void };

describe('Login Page', () => {
  beforeAll(async () => {
    server = await startMock({ port: 8000, scene: 'login' });
  });

  afterAll(() => server?.close());

  it('shows only the AntFlow account login', async () => {
    const historyRef = React.createRef<any>();
    const root = render(
      <TestBrowser historyRef={historyRef} location={{ pathname: '/user/login' }} />,
    );

    await root.findByText('AntFlow 审批管理后台');
    expect(root.queryByText('手机号登录')).not.toBeInTheDocument();
    expect(root.queryByText('其他登录方式')).not.toBeInTheDocument();
    expect(root.getByPlaceholderText('请输入账号')).toBeInTheDocument();
    expect(root.getByPlaceholderText('请输入密码')).toBeInTheDocument();

    act(() => historyRef.current?.push('/user/login'));
    root.unmount();
  });

  it('keeps the authenticated redirect flow', async () => {
    const historyRef = React.createRef<any>();
    const root = render(
      <TestBrowser historyRef={historyRef} location={{ pathname: '/user/login' }} />,
    );

    fireEvent.change(await root.findByPlaceholderText('请输入账号'), {
      target: { value: 'admin' },
    });
    fireEvent.change(root.getByPlaceholderText('请输入密码'), {
      target: { value: 'ant.design' },
    });
    await act(async () => {
      fireEvent.click(root.getByRole('button', { name: '登录运营中心' }));
    });

    await root.findByText('审批运营中心', undefined, { timeout: 10000 });
    expect(localStorage.getItem('antflow-token')).toBe('test-access-token');
    root.unmount();
  });
});
