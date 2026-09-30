import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ReportDashboardPage from './Dashboard';

const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@umijs/max', () => ({ request }));
vi.mock('@ant-design/pro-components', () => ({
  PageContainer: ({ children }: { children: React.ReactNode }) => <main>{children}</main>,
}));

// G2 在 happy-dom 里没有 canvas，这里只验"喂给图的数据对不对"——那才是这一页自己写的逻辑。
vi.mock('@ant-design/plots', () => ({
  Line: ({ data, seriesField }: any) => (
    <div data-testid="line" data-count={data.length} data-series={seriesField} />
  ),
  Column: ({ data }: any) => <div data-testid="column" data-count={data.length} />,
  Pie: ({ data }: any) => <div data-testid="pie" data-count={data.length} />,
}));

const summary = {
  totals: { started: 3, approved: 2, rejected: 1, withdrawn: 0, running: 0, other: 0,
    approvalRate: 66.7, avgDurationHours: 2.5 },
  byForm: [
    { formDefId: 5, formName: '客户信息登记表', started: 2, approved: 2, rejected: 0,
      withdrawn: 0, running: 0, other: 0, approvalRate: 100, avgDurationHours: 2 },
    { formDefId: 6, formName: '员工入职表', started: 1, approved: 0, rejected: 1,
      withdrawn: 0, running: 0, other: 0, approvalRate: 0, avgDurationHours: 3 },
  ],
  byDepartment: [
    { deptId: 4, deptName: '技术部', started: 2, approved: 2, rejected: 0, withdrawn: 0,
      running: 0, other: 0, approvalRate: 100, avgDurationHours: 2 },
    // 没有已决记录的部门：通过率是 null，不该画成 0% 的柱子
    { deptId: null, deptName: null, started: 1, approved: 0, rejected: 0, withdrawn: 0,
      running: 1, other: 0, approvalRate: null, avgDurationHours: null },
  ],
  byDay: [
    { date: '2026-09-27', started: 2, finished: 1 },
    { date: '2026-09-28', started: 1, finished: 2 },
  ],
};

// happy-dom 没有布局（clientWidth 恒为 0）也没有 ResizeObserver —— 图表是"量到宽度才挂载"的，
// 所以这里补个桩，让它报告一个确定的宽度。
class ResizeObserverStub {
  constructor(private readonly callback: (entries: Array<{ contentRect: { width: number } }>) => void) {}
  observe() { this.callback([{ contentRect: { width: 800 } }]); }
  disconnect() {}
  unobserve() {}
}
(globalThis as any).ResizeObserver = ResizeObserverStub;

describe('数据看板', () => {
  it('趋势图把每天拆成发起/完成两条，部门图跳过没有通过率的行', async () => {
    request.mockImplementation((url: string) => {
      if (url === '/api/reports/approval-summary') return Promise.resolve(summary);
      return Promise.resolve({ records: [] });
    });

    render(<ReportDashboardPage />);

    await waitFor(() => expect(screen.getByTestId('line')).toBeInTheDocument());
    expect(screen.getByTestId('line')).toHaveAttribute('data-series', 'type');
    // 2 天 × 2 条序列 = 4 个点
    expect(screen.getByTestId('line')).toHaveAttribute('data-count', '4');
    // 两个部门里只有一个有通过率
    expect(screen.getByTestId('column')).toHaveAttribute('data-count', '1');
    // 饼图按表单：两张表单两个扇区
    expect(screen.getByTestId('pie')).toHaveAttribute('data-count', '2');
  });

  it('这段时间没有数据时只给一句提示，不画空坐标系', async () => {
    request.mockImplementation((url: string) => {
      if (url === '/api/reports/approval-summary') {
        return Promise.resolve({ totals: { started: 0, approved: 0, rejected: 0, withdrawn: 0,
          running: 0, other: 0, approvalRate: null, avgDurationHours: null },
        byForm: [], byDepartment: [], byDay: [] });
      }
      return Promise.resolve({ records: [] });
    });

    render(<ReportDashboardPage />);

    expect(await screen.findByText(/这段时间没有流程数据/)).toBeInTheDocument();
    expect(screen.queryByTestId('line')).not.toBeInTheDocument();
    expect(screen.queryByTestId('pie')).not.toBeInTheDocument();
  });

  it('有提交但没有已决时：柱状图给空态而不是 0% 的柱子', async () => {
    request.mockImplementation((url: string) => {
      if (url === '/api/reports/approval-summary') {
        return Promise.resolve({ ...summary,
          byDepartment: [{ ...summary.byDepartment[1], deptId: 4, deptName: '技术部' }] });
      }
      return Promise.resolve({ records: [] });
    });

    render(<ReportDashboardPage />);

    expect(await screen.findByText('这段时间还没有已决的流程')).toBeInTheDocument();
    expect(screen.queryByTestId('column')).not.toBeInTheDocument();
  });
});
