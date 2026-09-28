import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within } from '@testing-library/react';
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
  id: 7, code: 'ces', name: '城市列表', status: 'ACTIVE', version: 3,
  updatedAt: '2026-09-28T02:30:00Z',
  publishedVersionId: 302, publishedVersionNo: 2, rowCount: 13,
  anyPublishedVersion: true, inUseFormCount: 2,
};

const other = {
  id: 8, code: 'lsb', name: '另一个源', status: 'ACTIVE', version: 1,
  updatedAt: '2026-09-27T02:30:00Z',
  publishedVersionId: 901, publishedVersionNo: 1, rowCount: 4,
  anyPublishedVersion: true, inUseFormCount: 0,
};

const detail = {
  source,
  // 三种状态各一个：待发布 v3、已发布 v2、已停用 v1。
  versions: [
    { id: 303, versionNo: 3, status: 'DRAFT', columns: ['招聘单位'], rowCount: 5,
      originalName: '新表.xlsx', note: '补了两家单位' },
    { id: 302, versionNo: 2, status: 'PUBLISHED', columns: ['招聘单位'], rowCount: 13,
      originalName: '旧表.xlsx', publishedAt: '2026-09-20T02:30:00Z', publishedByName: '张三' },
    { id: 301, versionNo: 1, status: 'PUBLISHED', columns: ['招聘单位'], rowCount: 13,
      originalName: '旧表.xlsx', disabledAt: '2026-09-23T00:00:00Z' },
  ],
  userIds: [], roleIds: [], forms: [{ id: 1, code: 'F1', name: '表单一' }],
  // v2 被两张表单用着，v1 和待发布的 v3 没人用。
  versionUsage: [
    { versionId: 302, forms: [{ id: 1, code: 'F1', name: '表单一' }, { id: 2, code: 'F2', name: '表单二' }] },
  ],
  deletable: false,
  deleteBlockedReason: '已发布过 2 个版本，只能停用',
};

const otherDetail = {
  source: other,
  versions: [
    { id: 901, versionNo: 1, status: 'PUBLISHED', columns: ['col'], rowCount: 4, originalName: 'a.xlsx' },
  ],
  userIds: [], roleIds: [], forms: [], versionUsage: [], deletable: true,
  deleteBlockedReason: null,
};

function mockApi() {
  request.mockImplementation((url: string) => {
    if (url === '/api/option-sources') return Promise.resolve([source, other]);
    if (url === '/api/option-sources/7') return Promise.resolve(detail);
    if (url === '/api/option-sources/8') return Promise.resolve(otherDetail);
    if (url === '/api/forms/definitions') return Promise.resolve({ records: detail.forms });
    if (url === '/api/option-sources/7/versions/302/diff') {
      return Promise.resolve({
        versionNo: 2, previousVersionNo: 1, columnChanges: ['删除列：备注'],
        added: [{ 招聘单位: '新单位' }], removed: [{ 招聘单位: '老单位' }],
        addedTotal: 1, removedTotal: 1, truncated: false,
      });
    }
    return Promise.resolve([]);
  });
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><App><OptionSources /></App></QueryClientProvider>);
}

/** 抽屉里的内容：列表行与抽屉里会出现同样的字（v2 / 已停用），断言必须限定范围。 */
const drawerBody = () => document.querySelector('.ant-drawer-body') as HTMLElement;

/** 列表里某一行的「版本」按钮（同名按钮每行一个）。 */
async function openVersions(rowName: string) {
  const name = await screen.findByText(rowName);
  const row = name.closest('tr') as HTMLElement;
  fireEvent.click(within(row).getByText('版本'));
  return row;
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
    expect(screen.getByText('新建数据源')).toBeInTheDocument();
  });

  it('列表把状态、当前版本、引用情况、更新时间摊在一行里', async () => {
    mockApi();
    renderPage();

    const name = await screen.findByText('城市列表');
    const row = name.closest('tr') as HTMLElement;
    expect(within(row).getByText('可使用')).toBeInTheDocument();
    expect(within(row).getByText('v2')).toBeInTheDocument();
    expect(within(row).getByText('2 张表单在用')).toBeInTheDocument();
    expect(within(row).getByText('2026-09-28 10:30')).toBeInTheDocument();
    // 未被使用的那行也要说清楚，不能空着让人猜。
    const idle = (await screen.findByText('另一个源')).closest('tr') as HTMLElement;
    expect(within(idle).getByText('未被使用')).toBeInTheDocument();
  });

  it('抽屉里的版本卡片按状态给出操作，并分开写每版在用的表单', async () => {
    mockApi();
    renderPage();

    await openVersions('城市列表');

    // 详情铺开了（这是只有详情才有的区块）。
    expect(await screen.findByText('引用与权限')).toBeInTheDocument();
    const body = drawerBody();
    // v2 是已发布的最新版：只标一次。
    expect(within(body).getAllByText('最新')).toHaveLength(1);
    // 已发布/已停用各一个：查看数据 + 取消发布；待发布那个：发布 + 丢弃。
    expect(within(body).getAllByText('查看数据')).toHaveLength(2);
    expect(within(body).getAllByText('取消发布')).toHaveLength(2);
    expect(within(body).getByText('发布')).toBeInTheDocument();
    expect(within(body).getByText('丢弃')).toBeInTheDocument();
    // 三种状态都能看出来，操作跟着状态走：已停用的给「启用」，未停用的给「停用」。
    expect(within(body).getByText('待发布')).toBeInTheDocument();
    expect(within(body).getByText('已发布')).toBeInTheDocument();
    expect(within(body).getByText('已停用')).toBeInTheDocument();
    expect(within(body).getByText('停用')).toBeInTheDocument();
    expect(within(body).getByText('启用')).toBeInTheDocument();
    // 变更说明与发布人（V50 + publishedByName）。
    expect(within(body).getByText('变更说明：补了两家单位')).toBeInTheDocument();
    expect(within(body).getByText(/张三/)).toBeInTheDocument();
    // 在用的表单是**按版本**列出来的，不能用源级引用清单摊到每一版。
    expect(within(body).getByText('表单一')).toBeInTheDocument();
    expect(within(body).getByText('表单二')).toBeInTheDocument();
    expect(within(body).getAllByText('未使用')).toHaveLength(2);
  });

  it('停用的源给「启用」，删除弹窗说清为什么不能删', async () => {
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

    const row = await openVersions('城市列表');
    expect(await screen.findByText('引用与权限')).toBeInTheDocument();
    // 停用状态不能只有一个灰点——列表那一行就要写出来。
    expect(within(row).getByText('已停用')).toBeInTheDocument();

    fireEvent.click(screen.getByLabelText('更多操作'));

    // 版本卡片里也有「启用」（已停用的版本），所以这里限定在 ⋯ 菜单里找。
    const menu = await screen.findByRole('menu');
    expect(within(menu).getByText('启用')).toBeInTheDocument();

    // 删除一律开弹窗：能不能删、为什么、牵扯谁都在那里说清，确认按钮跟着置灰。
    fireEvent.click(within(menu).getByText('删除…'));
    expect(await screen.findByText('不能删除：已发布过 2 个版本，只能停用')).toBeInTheDocument();
    // 引用明细摊开：v2 被两张表单在用，两条都在（不是在源级引用清单上估个数）。
    expect(screen.getAllByText('在用（字段绑着）')).toHaveLength(2);
    expect(screen.getByRole('button', { name: '确认删除' })).toBeDisabled();
  });

  it('换一个源就换一份详情，不会残留上一个源', async () => {
    // 归属问题：抽屉按 sourceId 重建，上一个源的响应落不到新源上。
    mockApi();
    renderPage();

    await openVersions('城市列表');
    expect(await screen.findByText('引用与权限')).toBeInTheDocument();
    expect(within(drawerBody()).getByText('变更说明：补了两家单位')).toBeInTheDocument();

    // 直接把源切过去（不经过关闭）：抽屉按 key 重建，A 的详情不该留在 B 里。
    await openVersions('另一个源');
    expect(await screen.findByText('引用与权限')).toBeInTheDocument();
    // 列表行里本来就有 v2，所以断言限定在抽屉里。
    const body = drawerBody();
    expect(within(body).queryByText('变更说明：补了两家单位')).not.toBeInTheDocument();
    expect(within(body).queryByText('v2')).not.toBeInTheDocument();
    expect(within(body).getByText('v1')).toBeInTheDocument();
  });

  it('有上一版才给「对比」，点开列出增删行', async () => {
    mockApi();
    renderPage();

    await openVersions('城市列表');
    expect(await screen.findByText('引用与权限')).toBeInTheDocument();

    const body = drawerBody();
    // v1 是第一版，没有上一版 → 不该有对比按钮。
    expect(within(body).queryByText(/对比 v0/)).not.toBeInTheDocument();
    expect(within(body).getAllByText(/对比 v\d/)).toHaveLength(1);
    expect(within(body).getByText('对比 v1')).toBeInTheDocument();

    fireEvent.click(within(body).getByText('对比 v1'));

    expect(await screen.findByText('版本对比 · v1 → v2')).toBeInTheDocument();
    expect(screen.getByText(/列结构有变化/)).toBeInTheDocument();
    expect(screen.getByText('删除列：备注')).toBeInTheDocument();
    expect(screen.getByText('招聘单位=新单位')).toBeInTheDocument();
    expect(screen.getByText('招聘单位=老单位')).toBeInTheDocument();
  });
});
