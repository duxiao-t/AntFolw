import { App } from 'antd';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import Login, { getSafeRedirectUrl } from './index';

// 原来这里有一份 Umi 测试运行器专用的 login.test.tsx（import @@/requestRecordMock 与 @@/testBrowser），
// 它被 vitest.config.ts 排除、仓库里也没有 Umi 的 jest 运行器 → CI 永远不会执行，只留下一份孤儿快照。
// 现在改成 vitest 原生用例：只 mock 页面真正用到的 4 个 umi API。
vi.mock('@umijs/max', () => ({
  Helmet: () => null,
  request: vi.fn(() => Promise.resolve([])),
  useIntl: () => ({
    formatMessage: ({ defaultMessage }: { defaultMessage?: string }) => defaultMessage ?? '',
  }),
  useModel: () => ({ initialState: null, setInitialState: vi.fn() }),
}));

describe('login page', () => {
  it('only accepts same-origin relative redirects', () => {
    const cases: Array<[string | null, string]> = [
      [null, '/'],
      ['', '/'],
      ['//evil.com', '/'],
      ['/\\evil.com', '/'],
      ['https://evil.com', '/'],
      ['javascript:alert(1)', '/'],
      ['/proc/detail?id=1#anchor', '/proc/detail?id=1#anchor'],
    ];
    for (const [input, expected] of cases) {
      expect(getSafeRedirectUrl(input), `redirect=${String(input)}`).toBe(expected);
    }
  });

  it('offers only the account login form', async () => {
    render(
      <App>
        <Login />
      </App>,
    );

    expect(
      await screen.findByRole('heading', { name: 'AntFlow 审批管理后台' }),
    ).toBeInTheDocument();
    expect(screen.getByPlaceholderText('请输入账号')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('请输入密码')).toBeInTheDocument();
    // 旧用例的意图：不能出现手机号登录/第三方登录等入口（只有账号密码一条路）
    expect(screen.queryByText('手机号登录')).not.toBeInTheDocument();
    expect(screen.queryByText('其他登录方式')).not.toBeInTheDocument();
  });
});
