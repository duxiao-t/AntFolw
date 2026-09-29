import { render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import dayjs from 'dayjs';
import ReportCenterPage, { rangeParams, tzOffsetMinutes } from './Center';

const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('@umijs/max', () => ({ request }));
vi.mock('@ant-design/pro-components', () => ({
  PageContainer: ({ children }: { children: React.ReactNode }) => <main>{children}</main>,
}));

const summary = {
  totals: { started: 4, approved: 2, rejected: 1, withdrawn: 0, running: 1, other: 0,
    approvalRate: 66.7, avgDurationHours: 2.7 },
  byForm: [{ formDefId: 5, formName: '客户信息登记表', started: 4, approved: 2, rejected: 1,
    withdrawn: 0, running: 1, other: 0, approvalRate: 66.7, avgDurationHours: 2.7 }],
  byDepartment: [{ deptId: 4, deptName: '技术部', started: 4, approved: 2, rejected: 1,
    withdrawn: 0, running: 1, other: 0, approvalRate: 66.7, avgDurationHours: 2.7 }],
  byDay: [{ date: '2026-09-27', started: 4, finished: 3 }],
};

describe('报表中心', () => {
  it('时间范围含首尾，并且带客户端时区（库里按 UTC 存）', () => {
    const today = dayjs('2026-09-29');
    expect(rangeParams(30, today)).toEqual({ from: '2026-08-31', to: '2026-09-29' });
    expect(rangeParams(7, today)).toEqual({ from: '2026-09-23', to: '2026-09-29' });
    // 东八区是 -(-480) = 480；这条保证传给后端的是"东为正"的分钟数。
    expect(tzOffsetMinutes()).toBe(-new Date().getTimezoneOffset());
  });

  it('一块横条仪表盘 + 两张表，通过率只出现一次不另画图', async () => {
    request.mockImplementation((url: string) => {
      if (url === '/api/reports/approval-summary') return Promise.resolve(summary);
      return Promise.resolve({ records: [{ id: 5, name: '客户信息登记表' }] });
    });

    render(<ReportCenterPage />);

    // 合计条：一行六个格子（「提交」在表头里也有，所以认那几个只属于合计条的字）
    await waitFor(() => expect(screen.getByText('平均审批耗时')).toBeInTheDocument());
    expect(screen.getByText('撤回 / 其它')).toBeInTheDocument();
    expect(screen.getByText('2.7')).toBeInTheDocument();
    // 两张表各一行，表头共两套
    expect(screen.getAllByText('客户信息登记表').length).toBeGreaterThan(0);
    expect(screen.getByText('技术部')).toBeInTheDocument();
    // 通过率只出现两处（两张表各一行）——总数条里没有重复一份
    expect(screen.getAllByText('66.7%')).toHaveLength(2);
    expect(screen.getByText('按表单')).toBeInTheDocument();
    expect(screen.getByText('按部门')).toBeInTheDocument();
  });

  it('这段时间一条数据都没有时，给一句能照着做的话', async () => {
    request.mockImplementation((url: string) => {
      if (url === '/api/reports/approval-summary') {
        return Promise.resolve({ totals: { started: 0, approved: 0, rejected: 0, withdrawn: 0,
          running: 0, other: 0, approvalRate: null, avgDurationHours: null },
        byForm: [], byDepartment: [], byDay: [] });
      }
      return Promise.resolve({ records: [] });
    });

    render(<ReportCenterPage />);

    expect(await screen.findByText(/这段时间没有流程数据/)).toBeInTheDocument();
    expect(screen.queryByText('按表单')).not.toBeInTheDocument();
  });

  it('没有已决记录时通过率显示 — 而不是 0%', async () => {
    request.mockImplementation((url: string) => {
      if (url === '/api/reports/approval-summary') {
        return Promise.resolve({
          ...summary,
          totals: { ...summary.totals, approved: 0, rejected: 0, approvalRate: null },
          // 有提交、但一条都没决：通过率是 null，不是 0%。
          byForm: [{ ...summary.byForm[0], approved: 0, rejected: 0, approvalRate: null }],
        });
      }
      return Promise.resolve({ records: [] });
    });

    render(<ReportCenterPage />);

    await waitFor(() => expect(screen.getByText('按表单')).toBeInTheDocument());
    const row = (await screen.findByText('客户信息登记表')).closest('tr') as HTMLElement;
    expect(within(row).getByText('—')).toBeInTheDocument();
  });
});
