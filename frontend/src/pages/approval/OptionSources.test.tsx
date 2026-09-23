import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { App } from 'antd';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import OptionSources from './OptionSources';

const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@umijs/max', () => ({
  request,
  history: { push: vi.fn() },
  Link: ({ children }: { children: React.ReactNode }) => children,
}));
// PageContainer 会把 antd 的日历 locale 一起拖进来，在 vitest 下解析不了。
vi.mock('@ant-design/pro-components', () => ({
  PageContainer: ({ children }: { children: React.ReactNode }) => <main>{children}</main>,
}));

const source = {
  id: 7, code: 'ces', name: 'ces', status: 'ACTIVE', version: 3,
  publishedVersionId: 302, publishedVersionNo: 2, rowCount: 13, formCount: 1,
};

const detail = {
  source,
  versions: [
    { id: 303, versionNo: 3, status: 'DRAFT', columns: ['招聘单位'], rowCount: 5, originalName: '新表.xlsx' },
    { id: 302, versionNo: 2, status: 'PUBLISHED', columns: ['招聘单位'], rowCount: 13, originalName: '旧表.xlsx' },
    { id: 301, versionNo: 1, status: 'PUBLISHED', columns: ['招聘单位'], rowCount: 13, originalName: '旧表.xlsx' },
  ],
  userIds: [], roleIds: [], forms: [{ id: 1, code: 'F1', name: '表单一' }], deletable: false,
  deleteBlockedReason: '已发布过 2 个版本，只能停用',
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><App><OptionSources /></App></QueryClientProvider>);
}

describe('选项数据源', () => {
  beforeEach(() => {
    request.mockReset();
  });

  it('一个源都没有时，给三步引导而不是空白', async () => {
    request.mockImplementation((url: string) => {
      if (url === '/api/option-sources') return Promise.resolve([]);
      throw new Error(`Unexpected request: ${url}`);
    });

    renderPage();

    expect(await screen.findByText('三步开始用')).toBeInTheDocument();
    expect(screen.getByText('导入并发布版本')).toBeInTheDocument();
    expect(screen.getByText('在表单里引用')).toBeInTheDocument();
    expect(screen.getAllByText('新建数据源')).toHaveLength(2); // 引导里一条 + 下面的按钮
    expect(screen.getByRole('button', { name: /新建数据源/ })).toBeInTheDocument();
    expect(screen.getByText('还没有数据源')).toBeInTheDocument();
  });

  it('选中数据源后，版本表标出最新版并按状态给出操作', async () => {
    request.mockImplementation((url: string) => {
      if (url === '/api/option-sources') return Promise.resolve([source]);
      if (url === '/api/option-sources/7') return Promise.resolve(detail);
      if (url === '/api/forms/definitions') return Promise.resolve({ records: detail.forms });
      return Promise.resolve([]);
    });

    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: /ces/ }));

    // 详情铺开了（这是只有详情才有的卡片）。
    expect(await screen.findByText('引用与权限')).toBeInTheDocument();
    // v2 是已发布的最新版：只标一次，v1 不该也带上。
    expect(screen.getAllByText('最新')).toHaveLength(1);
    // 已发布的两个版本：查看数据 + 取消发布；待发布那个：发布 + 丢弃。
    expect(screen.getAllByText('查看数据')).toHaveLength(2);
    expect(screen.getAllByText('取消发布')).toHaveLength(2);
    expect(screen.getByText('发布')).toBeInTheDocument();
    expect(screen.getByText('丢弃')).toBeInTheDocument();
    // 引用关系回填到多选框，标签是「名称 · 编码」。
    await waitFor(() => expect(screen.getByText('表单一 · F1')).toBeInTheDocument());
  });

  it('停用的源给「启用」，不可删时删除置灰并说清原因', async () => {
    request.mockImplementation((url: string) => {
      if (url === '/api/option-sources') {
        return Promise.resolve([{ ...source, status: 'DISABLED' }]);
      }
      if (url === '/api/option-sources/7') {
        return Promise.resolve({ ...detail, source: { ...source, status: 'DISABLED' } });
      }
      return Promise.resolve([]);
    });

    renderPage();

    fireEvent.click(await screen.findByRole('button', { name: /ces/ }));
    expect(await screen.findByText('引用与权限')).toBeInTheDocument();
    // 停用状态不能只有一个灰点，列表那一项也要写出来。
    expect(screen.getByRole('button', { name: /已停用 · v2/ })).toBeInTheDocument();

    fireEvent.click(screen.getByLabelText('更多操作'));

    expect(await screen.findByText('启用')).toBeInTheDocument();
    // 删不掉时不再整个藏起来，而是置灰 + 一行原因。
    expect(screen.getByText('已发布过 2 个版本，只能停用')).toBeInTheDocument();
    const deleteItem = screen.getByText('删除').closest('.ant-dropdown-menu-item');
    expect(deleteItem).toHaveClass('ant-dropdown-menu-item-disabled');
  });
});
