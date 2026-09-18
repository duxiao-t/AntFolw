import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import dayjs from 'dayjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import RecordListPage from './RecordList';

const { requestMock, pushMock } = vi.hoisted(() => ({
  requestMock: vi.fn(), pushMock: vi.fn(),
}));
vi.mock('@umijs/max', () => ({ request: requestMock, history: { push: pushMock } }));
vi.mock('@ant-design/pro-components', () => ({
  PageContainer: ({ children }: any) => <main>{children}</main>,
  ProTable: ({ columns, request, onRow }: any) => {
    const records = [
      {
        id: 41, formName: '采购申请', formCode: 'purchase', businessNo: '000000000041',
        applicantName: '林晓', applicantDepartment: '采购部', applicantEmployeeNo: '000009',
        status: 'RUNNING', currentNodeId: 'manager',
        processSnapshot: { id: 'root', type: 'ROOT', children: {
          id: 'manager', type: 'APPROVAL', props: { name: '部门负责人审批' },
        } },
        startedAt: '2026-09-01T09:00:00+08:00', finishedAt: null,
      },
      {
        id: 42, formName: '已完成采购', status: 'APPROVED', currentNodeId: 'node_legacy',
        startedAt: '2026-09-01T09:00:00+08:00', finishedAt: '2026-09-01T10:30:00+08:00',
      },
      { id: 43, formName: '待修改采购', status: 'RUNNING', currentNodeId: '__rework__' },
      { id: 44, formName: '旧版采购', status: 'RUNNING', currentNodeId: 'node_missing' },
    ];
    const keyword = columns.find((column: any) => column.dataIndex === 'keyword');
    const status = columns.find((column: any) => column.dataIndex === 'status');
    const range = columns.find((column: any) => column.dataIndex === 'startedRange');
    const tableColumns = columns.filter((column: any) => !column.hideInTable);
    return <div>
      <input aria-label="关键词" placeholder={keyword.fieldProps.placeholder} />
      <button type="button" onClick={() => request({
        current: 1, pageSize: 20, keyword: ' 林晓 ', status: 'REWORK',
        ...range.search.transform(['2026-09-01', '2026-09-18']),
      })}>查询测试</button>
      <button type="button" onClick={() => request({ current: 1, pageSize: 20 })}>重置测试</button>
      <span data-testid="status-options">{Object.values(status.valueEnum)
        .map((item: any) => item.text).join('|')}</span>
      <table><thead><tr>{tableColumns.map((column: any) =>
        <th key={column.key ?? column.dataIndex ?? column.title}>{column.title}</th>)}</tr></thead>
        <tbody>{records.map((record: any) => <tr key={record.id}
          onClick={onRow(record).onClick} aria-label={`记录 ${record.id}`}>{tableColumns.map((column: any) =>
            <td key={column.key ?? column.dataIndex ?? column.title}>
              {column.render ? column.render(record[column.dataIndex], record) : record[column.dataIndex]}
            </td>)}</tr>)}</tbody></table>
    </div>;
  },
}));
vi.mock('antd', () => ({
  Button: ({ children, onClick }: any) => <button type="button" onClick={onClick}>{children}</button>,
  Tag: ({ children }: any) => <span>{children}</span>,
  Tooltip: ({ children }: any) => children,
}));

beforeEach(() => {
  requestMock.mockReset().mockResolvedValue({ records: [], total: 0 });
  pushMock.mockReset();
});

describe('approval records business view', () => {
  it('shows business identity, readable stages and duration without technical ID columns', () => {
    render(<RecordListPage />);

    expect(screen.getByText('采购申请')).toBeInTheDocument();
    expect(screen.getByText('单号 000000000041 · 实例 #41')).toBeInTheDocument();
    expect(screen.getByText('林晓')).toBeInTheDocument();
    expect(screen.getByText('采购部 · 工号 000009')).toBeInTheDocument();
    expect(screen.getByText('部门负责人审批')).toBeInTheDocument();
    expect(screen.getByText('流程已结束')).toBeInTheDocument();
    expect(screen.getByText('1小时 30分钟')).toBeInTheDocument();
    expect(screen.getByText('修改后重提')).toBeInTheDocument();
    expect(screen.getByText('审批处理中')).toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: '实例ID' })).not.toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: '发起人ID' })).not.toBeInTheDocument();
    expect(screen.queryByText('node_legacy')).not.toBeInTheDocument();
    expect(screen.queryByText('node_missing')).not.toBeInTheDocument();
    expect(screen.getByTestId('status-options')).toHaveTextContent('进行中（含待修改）|待修改');

    fireEvent.click(screen.getAllByRole('button', { name: '查看详情' })[0]);
    expect(pushMock).toHaveBeenCalledExactlyOnceWith('/proc/41');
    fireEvent.click(screen.getByRole('row', { name: '记录 42' }));
    expect(pushMock).toHaveBeenLastCalledWith('/proc/42');
  });

  it('submits trimmed keyword, status and inclusive calendar dates then resets', async () => {
    render(<RecordListPage />);
    fireEvent.click(screen.getByRole('button', { name: '查询测试' }));
    await waitFor(() => expect(requestMock).toHaveBeenLastCalledWith('/api/instances', {
      params: { scope: 'authorized', page: 1, size: 20, keyword: '林晓', status: 'REWORK',
        from: dayjs('2026-09-01').startOf('day').toISOString(),
        to: dayjs('2026-09-19').startOf('day').toISOString() },
    }));
    fireEvent.click(screen.getByRole('button', { name: '重置测试' }));
    await waitFor(() => expect(requestMock).toHaveBeenLastCalledWith('/api/instances', {
      params: { scope: 'authorized', page: 1, size: 20, keyword: undefined,
        status: undefined, from: undefined, to: undefined },
    }));
  });
});
