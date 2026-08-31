import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { ApprovalRecords, approvalSummaryLabel } from './ApprovalRecords';
import type { ApprovalRecord, MobileHistoryItem } from './tasks.api';

const approvedRecord: ApprovalRecord = {
  id: 'approved',
  taskId: 1,
  nodeName: '班组长审核',
  status: 'APPROVED',
  operatorName: '刘海峰',
  employeeNo: '000108',
  department: '热处理一组',
  comment: '已核对',
  receivedAt: '2026-07-30T08:42:00+08:00',
  completedAt: '2026-07-30T09:06:00+08:00',
};
const records: ApprovalRecord[] = [
  approvedRecord,
  {
    id: 'rejected',
    taskId: 2,
    nodeName: '车间主任审批',
    status: 'REJECTED',
    operatorName: '陈建国',
    employeeNo: '000006',
    department: '生产制造部',
    comment: '数据不一致',
    receivedAt: '2026-07-30T09:06:00+08:00',
    completedAt: '2026-07-30T09:12:00+08:00',
  },
  {
    id: 'processing',
    taskId: 3,
    nodeName: '班组长审核',
    status: 'PROCESSING',
    operatorName: '刘海峰',
    employeeNo: '000108',
    department: '热处理一组',
    receivedAt: '2026-07-30T09:12:00+08:00',
  },
];

describe('ApprovalRecords', () => {
  it('uses red only for rejected and blue only for processing records', () => {
    const { container } = render(<ApprovalRecords records={records} />);

    expect(
      screen.getByText('已驳回').closest('.approval-record-card'),
    ).toHaveClass('approval-record-card--rejected');
    expect(
      screen.getByText('审批中').closest('.approval-record-card'),
    ).toHaveClass('approval-record-card--current');
    expect(
      screen.getByText('已通过').closest('.approval-record-card'),
    ).not.toHaveClass(
      'approval-record-card--current',
      'approval-record-card--rejected',
    );
    expect(
      container.querySelectorAll('.approval-record-card--current'),
    ).toHaveLength(1);
    expect(
      container.querySelectorAll('.approval-record-card--rejected'),
    ).toHaveLength(1);
  });

  it('shows counts while running and only completion after full approval', () => {
    expect(
      approvalSummaryLabel({
        flowedCount: 3,
        completedCount: 2,
        processingCount: 1,
        complete: false,
      }),
    ).toBe('2 已完成 · 1 处理中');
    expect(
      approvalSummaryLabel({
        flowedCount: 3,
        completedCount: 3,
        processingCount: 0,
        complete: true,
      }),
    ).toBe('已完成');
  });

  it('shows the date and time for every approval node', () => {
    const { container } = render(<ApprovalRecords records={records} />);
    const times = container.querySelectorAll('time');

    expect(times).toHaveLength(records.length);
    for (const time of times) {
      expect(time).toHaveTextContent(/^\d{2}-\d{2} \d{2}:\d{2}/);
    }
    expect(times[2]).toHaveTextContent(/ 接收$/);
  });

  it('shows only the taken condition path and stacks three parallel branches in definition order', () => {
    const processSnapshot = {
      id: 'root',
      type: 'ROOT',
      children: {
        id: 'parallel',
        type: 'PARALLEL',
        branchs: [
          {
            id: 'branch-1',
            type: 'BRANCH',
            name: '财务线',
            children: { id: 'parallel-1', type: 'APPROVAL' },
          },
          {
            id: 'branch-2',
            type: 'BRANCH',
            name: '行政线',
            children: { id: 'parallel-2', type: 'APPROVAL' },
          },
          {
            id: 'branch-3',
            type: 'BRANCH',
            name: '业务线',
            children: { id: 'parallel-3', type: 'APPROVAL' },
          },
        ],
        children: {
          id: 'conditions',
          type: 'CONDITIONS',
          branchs: [
            {
              id: 'matched',
              type: 'CONDITION',
              props: {
                groups: [
                  {
                    groupType: 'AND',
                    conditions: [{ field: 'days', operator: '>', value: 3 }],
                  },
                ],
              },
              children: { id: 'matched-approval', type: 'APPROVAL' },
            },
            {
              id: 'default',
              type: 'CONDITION',
              name: '未触发分支',
              props: { isDefault: true },
              children: { id: 'default-approval', type: 'APPROVAL' },
            },
          ],
        },
      },
    };
    const flowRecords: ApprovalRecord[] = [
      {
        ...approvedRecord,
        id: 'p1',
        nodeId: 'parallel-1',
        nodeName: '并行审批一',
        parallelId: 'parallel',
        branchId: 'branch-1',
      },
      {
        ...approvedRecord,
        id: 'p2',
        nodeId: 'parallel-2',
        nodeName: '并行审批二',
        parallelId: 'parallel',
        branchId: 'branch-2',
        receivedAt: '2026-07-30T08:43:00+08:00',
      },
      {
        ...approvedRecord,
        id: 'p3',
        nodeId: 'parallel-3',
        nodeName: '并行审批三',
        parallelId: 'parallel',
        branchId: 'branch-3',
        receivedAt: '2026-07-30T08:44:00+08:00',
      },
      {
        ...approvedRecord,
        id: 'matched',
        nodeId: 'matched-approval',
        nodeName: '命中审批',
        receivedAt: '2026-07-30T09:10:00+08:00',
      },
    ];

    const { container } = render(
      <ApprovalRecords
        records={flowRecords}
        processSnapshot={processSnapshot}
        schema={[
          {
            id: 'days',
            type: 'number',
            label: '请假天数',
            props: { suffix: '天' },
          },
        ]}
      />,
    );

    expect(screen.getByText('条件判断：请假天数 > 3天')).toBeInTheDocument();
    expect(screen.queryByText('未触发分支')).not.toBeInTheDocument();
    expect(
      container.querySelectorAll('.approval-records__parallel-grid'),
    ).toHaveLength(1);
    expect(
      container.querySelector('.approval-records__parallel-grid'),
    ).toHaveClass('approval-records__parallel-grid--stacked');
    expect(
      container.querySelectorAll('.approval-records__parallel-branch'),
    ).toHaveLength(3);
    expect(
      [
        ...container.querySelectorAll('.approval-records__parallel-branch h4'),
      ].map((node) => node.textContent),
    ).toEqual(['财务线', '行政线', '业务线']);
    expect(
      screen.getByText('命中审批').closest('.approval-records__parallel-grid'),
    ).toBeNull();
  });

  it('uses two columns for exactly two parallel branches', () => {
    const processSnapshot = {
      id: 'parallel',
      type: 'PARALLEL',
      branchs: [
        {
          id: 'branch-1',
          type: 'BRANCH',
          children: { id: 'parallel-1', type: 'APPROVAL' },
        },
        {
          id: 'branch-2',
          type: 'BRANCH',
          children: { id: 'parallel-2', type: 'APPROVAL' },
        },
      ],
    };
    const parallelRecords: ApprovalRecord[] = [
      {
        ...approvedRecord,
        id: 'p1',
        nodeId: 'parallel-1',
        parallelId: 'parallel',
        branchId: 'branch-1',
      },
      {
        ...approvedRecord,
        id: 'p2',
        nodeId: 'parallel-2',
        parallelId: 'parallel',
        branchId: 'branch-2',
        status: 'REJECTED',
      },
    ];

    const { container } = render(
      <ApprovalRecords
        records={parallelRecords}
        processSnapshot={processSnapshot}
      />,
    );

    expect(
      container.querySelector('.approval-records__parallel-grid'),
    ).toHaveClass('approval-records__parallel-grid--two');
    expect(screen.getByText('分支 1')).toBeInTheDocument();
    expect(screen.getByText('分支 2')).toBeInTheDocument();
    expect(screen.getByText('重提交').closest('footer')).not.toBeNull();
  });

  it('separates resubmission rounds and never merges the same parallel gateway across rounds', () => {
    const processSnapshot = {
      id: 'parallel',
      type: 'PARALLEL',
      branchs: [
        { id: 'branch-1', type: 'BRANCH', children: { id: 'parallel-1', type: 'APPROVAL' } },
        { id: 'branch-2', type: 'BRANCH', children: { id: 'parallel-2', type: 'APPROVAL' } },
      ],
    };
    const { container } = render(
      <ApprovalRecords
        processSnapshot={processSnapshot}
        records={[
          { ...approvedRecord, id: 'round-1-a', nodeId: 'parallel-1', parallelId: 'parallel', branchId: 'branch-1', roundNo: 1 },
          { ...approvedRecord, id: 'round-1-b', nodeId: 'parallel-2', parallelId: 'parallel', branchId: 'branch-2', roundNo: 1, receivedAt: '2026-07-30T08:43:00+08:00' },
          { ...approvedRecord, id: 'round-2-a', nodeId: 'parallel-1', parallelId: 'parallel', branchId: 'branch-1', roundNo: 2, receivedAt: '2026-07-30T09:10:00+08:00' },
          { ...approvedRecord, id: 'round-2-b', nodeId: 'parallel-2', parallelId: 'parallel', branchId: 'branch-2', roundNo: 2, receivedAt: '2026-07-30T09:11:00+08:00' },
        ]}
      />,
    );

    expect(screen.getByText('第 1 次提交')).toBeInTheDocument();
    expect(screen.getByText('第 2 次提交')).toBeInTheDocument();
    expect(container.querySelectorAll('.approval-records__parallel-grid')).toHaveLength(2);
  });

  it('keeps long text in the two-line card summary and opens the full detail', async () => {
    const user = userEvent.setup();
    const comment = 'A'.repeat(120);
    const { container } = render(
      <ApprovalRecords records={[{ ...approvedRecord, comment }]} />,
    );

    const summary = screen.getByText(comment);
    expect(summary).toHaveClass('approval-record-card__comment');
    expect(summary.closest('.approval-record-card')).toBe(
      container.querySelector('.approval-record-card'),
    );
    await user.click(summary.closest('button') as HTMLButtonElement);
    expect(screen.getByRole('dialog', { name: '审批记录详情' })).toBeInTheDocument();
    expect(screen.getAllByText(comment)).toHaveLength(2);
  });

  it('adds only the newly entered level for nested condition paths', () => {
    const processSnapshot = {
      id: 'root',
      type: 'ROOT',
      children: {
        id: 'outer',
        type: 'CONDITIONS',
        branchs: [{
          id: 'outer-default',
          type: 'CONDITION',
          props: { isDefault: true },
          children: {
            id: 'outer-approval',
            type: 'APPROVAL',
            children: {
              id: 'inner',
              type: 'CONDITIONS',
              branchs: [{
                id: 'inner-default',
                type: 'CONDITION',
                props: { isDefault: true },
                children: { id: 'inner-approval', type: 'APPROVAL' },
              }],
            },
          },
        }],
      },
    };
    render(
      <ApprovalRecords
        records={[
          { ...approvedRecord, id: 'outer-record', nodeId: 'outer-approval' },
          {
            ...approvedRecord,
            id: 'inner-record',
            nodeId: 'inner-approval',
            receivedAt: '2026-07-30T09:10:00+08:00',
          },
        ]}
        processSnapshot={processSnapshot}
      />,
    );

    expect(screen.getAllByText('条件判断：其他情况')).toHaveLength(2);
  });

  it('renders transfer history as a display-only label', () => {
    const transfer: ApprovalRecord = {
      ...approvedRecord,
      id: 'transfer',
      operationKind: 'TRANSFER',
      sourceOperatorName: '原审批人',
      operatorName: '新审批人',
    };

    render(<ApprovalRecords records={[transfer]} />);

    expect(screen.getByText('（转交自：原审批人）')).toBeInTheDocument();
    expect(screen.getByText('转交记录').tagName).toBe('SPAN');
  });

  it('shows an invalidated parallel approval as void instead of approved', async () => {
    const user = userEvent.setup();
    const invalidated: ApprovalRecord = {
      ...approvedRecord,
      id: 'invalidated',
      operationKind: 'INVALIDATED',
    };

    render(<ApprovalRecords records={[invalidated]} />);

    const card = screen.getByText('已作废').closest('.approval-record-card');
    expect(card).toHaveClass('approval-record-card--rejected');
    expect(card).toHaveTextContent('审批作废');
    expect(screen.queryByText('已通过')).not.toBeInTheDocument();

    await user.click(card as HTMLElement);
    expect(screen.getByRole('dialog')).toHaveTextContent('已作废');
  });

  it('merges cc recipients and derives one completed automation record from history', () => {
    const ccRecords: ApprovalRecord[] = [
      {
        ...approvedRecord,
        id: 'cc-1',
        nodeId: 'cc',
        nodeName: '抄送',
        recordKind: 'CC',
        operatorName: '张三',
        receivedAt: '2026-07-30T10:00:00+08:00',
      },
      {
        ...approvedRecord,
        id: 'cc-2',
        nodeId: 'cc',
        nodeName: '抄送',
        recordKind: 'CC',
        operatorName: '李四',
        receivedAt: '2026-07-30T10:00:01+08:00',
      },
    ];
    const history: MobileHistoryItem[] = [
      {
        id: 1,
        toNodeId: 'trigger',
        action: 'TRIGGER_QUEUED',
        createdAt: '2026-07-30T10:01:00+08:00',
      },
      {
        id: 2,
        fromNodeId: 'trigger',
        action: 'TRIGGER_SUCCEEDED',
        createdAt: '2026-07-30T10:02:00+08:00',
      },
    ];
    const snapshot = {
      id: 'root',
      type: 'ROOT',
      children: { id: 'trigger', type: 'TRIGGER', name: '自动归档' },
    };

    const { container } = render(
      <ApprovalRecords
        records={ccRecords}
        history={history}
        processSnapshot={snapshot}
      />,
    );

    expect(screen.getByText('张三、李四')).toBeInTheDocument();
    expect(
      container.querySelectorAll('.approval-record-card--cc'),
    ).toHaveLength(1);
    expect(
      screen.getByText('自动归档').closest('.approval-record-card'),
    ).toHaveClass('approval-record-card--automation');
    expect(
      screen.getByText('自动归档').closest('.approval-record-card'),
    ).toHaveTextContent('已完成');
  });

  it('falls back to a plain timeline when the process snapshot is malformed', () => {
    const { container } = render(
      <ApprovalRecords records={records} processSnapshot="not-json" />,
    );

    expect(container.querySelectorAll('.approval-record-card')).toHaveLength(
      records.length,
    );
    expect(
      container.querySelector('.approval-records__parallel-grid'),
    ).toBeNull();
  });
});
