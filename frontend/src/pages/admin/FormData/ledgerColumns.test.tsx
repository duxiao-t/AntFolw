import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import AdminFormDataPage from './index';

const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@umijs/max', () => ({
  request,
  useLocation: () => ({ search: '' }),
  Link: ({ children }: { children: React.ReactNode }) => children,
}));

// 捕获 ProTable 拿到的 props：列定义与 request 映射都在里面，是这一页最容易悄悄写错的两处。
let tableProps: any;
vi.mock('@ant-design/pro-components', () => ({
  PageContainer: ({ children }: { children: React.ReactNode }) => <main>{children}</main>,
  ProTable: (props: any) => {
    tableProps = props;
    return <table />;
  },
}));

const row = {
  id: 1, formDefId: 5, formDefVersion: 1, businessNo: '000000000040', status: 'SUBMITTED',
  createdBy: 3, createdByName: '张三', createdByEmployeeNo: '000003',
  createdByDeptName: '技术部', fieldValues: [], createdAt: '2026-09-29T02:00:00Z',
};

async function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><AdminFormDataPage /></QueryClientProvider>);
  await Promise.resolve();
  return tableProps;
}

describe('台账列表的列与筛选', () => {
  beforeEach(() => {
    tableProps = undefined;
    request.mockReset();
    request.mockImplementation(() => Promise.resolve({ records: [row], total: 1 }));
  });

  it('提交人显示姓名，另有两列工号与部门', async () => {
    const props = await renderPage();
    const byTitle = (title: string) => props.columns.find((column: any) => column.title === title);

    expect(byTitle('提交人').dataIndex).toBe('createdByName');
    expect(byTitle('工号').dataIndex).toBe('createdByEmployeeNo');
    expect(byTitle('部门').dataIndex).toBe('createdByDeptName');

    // 渲染函数：姓名直接出，没有部门的人给一句人话而不是空单元格。
    expect(byTitle('提交人').render(null, row)).toBe('张三');
    expect(byTitle('工号').render(null, row)).toBe('000003');
    expect(byTitle('部门').render(null, { ...row, createdByDeptName: undefined }))
      .toHaveProperty('type', 'span');
  });

  it('提交人筛选发的是姓名关键字（不是数字 id）', async () => {
    const props = await renderPage();
    // ProTable 应用列的 search.transform 之后再进 request。
    const search = props.columns.find((column: any) => column.title === '提交人').search;
    expect(search.transform('张三')).toEqual({ submitterKeyword: '张三' });

    await props.request({ current: 1, pageSize: 20, submitterKeyword: '张三' });
    expect(request).toHaveBeenCalledWith('/api/forms/data/admin', {
      params: expect.objectContaining({ submitterKeyword: '张三', page: 1, size: 20 }),
    });
  });

  it('字段值直接渲染后端给的显示文本，不再自己取表单定义', async () => {
    // 显示文本（下拉选项名、检查项汇总）由后端按**每条记录自己的版本**解析。前端以前要额外
    // 请求一次表单定义，只有 form:data:read 的账号取定义会 403，只能把这条可选请求从全局错误
    // 处理里摘出去；现在这条请求已经不存在了。
    request.mockImplementation(() => Promise.resolve({
      records: [{ ...row, fieldValues: [
        { fieldId: 'f1', fieldName: '工艺', value: 'option_1', displayText: '车削', detailText: '车削' },
      ] }],
      total: 1,
    }));
    const props = await renderPage();
    // 字段列是**拿到数据后**才推出来的（列来自 fieldValues 的并集），所以要在 state 落定后
    // 重新读一次 ProTable 的 props，不能用手上那份旧的。
    await act(async () => { await props.request({ current: 1, pageSize: 20 }); });

    const fieldColumn = tableProps.columns.find((column: any) => column.title === '工艺');
    const withValue = { fieldValues: [
      { fieldId: 'f1', fieldName: '工艺', value: 'option_1', displayText: '车削', detailText: '车削' },
    ] };
    expect(fieldColumn.render(null, withValue)).toBe('车削');
    expect(fieldColumn.render(null, { fieldValues: [] })).toBe('—');
    expect(request.mock.calls.some(([url]) => String(url).includes('/api/forms/definitions/')))
      .toBe(false);
  });
});
