import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ApprovalRecords, approvalSummaryLabel } from './ApprovalRecords';
import type { ApprovalRecord } from './tasks.api';

const records: ApprovalRecord[] = [
  { id: 'approved', taskId: 1, nodeName: '班组长审核', status: 'APPROVED', operatorName: '刘海峰', employeeNo: '000108', department: '热处理一组', comment: '已核对', receivedAt: '2026-07-30T08:42:00+08:00', completedAt: '2026-07-30T09:06:00+08:00' },
  { id: 'rejected', taskId: 2, nodeName: '车间主任审批', status: 'REJECTED', operatorName: '陈建国', employeeNo: '000006', department: '生产制造部', comment: '数据不一致', receivedAt: '2026-07-30T09:06:00+08:00', completedAt: '2026-07-30T09:12:00+08:00' },
  { id: 'processing', taskId: 3, nodeName: '班组长审核', status: 'PROCESSING', operatorName: '刘海峰', employeeNo: '000108', department: '热处理一组', receivedAt: '2026-07-30T09:12:00+08:00' },
];

describe('ApprovalRecords', () => {
  it('uses red only for rejected and blue only for processing records', () => {
    const { container } = render(<ApprovalRecords records={records} />);

    expect(screen.getByText('已驳回').closest('.approval-record-card')).toHaveClass('approval-record-card--rejected');
    expect(screen.getByText('审批中').closest('.approval-record-card')).toHaveClass('approval-record-card--current');
    expect(screen.getByText('已通过').closest('.approval-record-card')).not.toHaveClass('approval-record-card--current', 'approval-record-card--rejected');
    expect(container.querySelectorAll('.approval-record-card--current')).toHaveLength(1);
    expect(container.querySelectorAll('.approval-record-card--rejected')).toHaveLength(1);
  });

  it('shows counts while running and only completion after full approval', () => {
    expect(approvalSummaryLabel({ flowedCount: 3, completedCount: 2, processingCount: 1, complete: false })).toBe('2 已完成 · 1 处理中');
    expect(approvalSummaryLabel({ flowedCount: 3, completedCount: 3, processingCount: 0, complete: true })).toBe('已完成');
  });
});
