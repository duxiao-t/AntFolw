import type { ApprovalRecord, ApprovalSummary } from './tasks.api';

// 审批记录的纯文案 / 视觉档位映射：被卡片、详情弹层、时间线与页面标题共用。
export function approvalSummaryLabel(summary: ApprovalSummary) {
  return summary.complete
    ? '已完成'
    : `${summary.completedCount} 已完成 · ${summary.processingCount} 处理中`;
}

export function visualKind(record: ApprovalRecord) {
  if (
    record.status === 'REJECTED' ||
    record.status === 'FAILED' ||
    record.status === 'RETURNED' ||
    record.operationKind === 'INVALIDATED'
  )
    return 'rejected';
  if (record.operationKind) return 'operation';
  if (record.recordKind === 'CC' || record.nodeType === 'CC') return 'cc';
  if (record.recordKind === 'AUTOMATION') return 'automation';
  if (record.status === 'PROCESSING') return 'current';
  return 'done';
}

export function itemTone(record: ApprovalRecord) {
  return ` approval-records__item--${visualKind(record)}`;
}

export function statusTone(record: ApprovalRecord) {
  return record.status === 'REJECTED' ||
    record.status === 'FAILED' ||
    record.operationKind === 'INVALIDATED'
    ? 'rejected'
    : record.status === 'PROCESSING' || record.status === 'RETURNED'
      ? 'current'
      : 'done';
}

export function operationTag(record: ApprovalRecord) {
  if (record.status === 'REJECTED') return '重提交';
  return (
    (
      {
        TRANSFER: '转交记录',
        DELEGATE: '委派记录',
        ADD_ASSIGNEE: '加签记录',
        INVALIDATED: '审批作废',
      } as Record<string, string>
    )[record.operationKind || ''] || ''
  );
}

export function operationSourceLabel(kind?: string | null) {
  return kind === 'TRANSFER'
    ? '转交自'
    : kind === 'ADD_ASSIGNEE'
      ? '加签人'
      : '委派自';
}

export function recordStatusLabel(record: ApprovalRecord) {
  if (record.recordKind === 'CC')
    return record.status === 'PROCESSING' ? '待查收' : '已抄送';
  if (record.operationKind === 'INVALIDATED') return '已作废';
  return (
    (
      {
        SUBMITTED: '已提交',
        PROCESSING: '审批中',
        APPROVED: '已通过',
        REJECTED: '已驳回',
        RETURNED: '待修改',
        RESUBMITTED: '已重新提交',
        COMPLETED: '已完成',
        FAILED: '执行失败',
      } as Record<string, string>
    )[record.status] ?? record.status
  );
}

export function defaultComment(record: ApprovalRecord) {
  if (record.recordKind === 'CC')
    return record.status === 'PROCESSING'
      ? '等待查收抄送内容。'
      : '已抄送相关同事。';
  if (record.operationKind === 'INVALIDATED')
    return '并行分支已驳回，本次同意已作废。';
  return (
    (
      {
        SUBMITTED: '已完成表单填写并提交审批。',
        PROCESSING: '等待处理当前审批节点。',
        APPROVED: '已完成本节点审批。',
        REJECTED: '已驳回至直接上一级。',
        RETURNED: '原单已退回，等待修改后重新提交。',
        RESUBMITTED: '原单修改完成并重新提交审批。',
        COMPLETED: '系统已完成自动处理。',
        FAILED: '自动处理失败。',
      } as Record<string, string>
    )[record.status] ?? '审批节点状态已更新。'
  );
}

export function automationComment(action: string) {
  return (
    (
      {
        DELAY_SCHEDULED: '等待设定时间后自动继续。',
        DELAY_COMPLETED: '延时结束，流程已自动继续。',
        TRIGGER_QUEUED: '自动任务等待执行。',
        TRIGGER_SUCCEEDED: '系统已完成自动处理。',
        TRIGGER_FAILED: '自动任务执行失败。',
      } as Record<string, string>
    )[action] ?? '系统已自动处理。'
  );
}

export function formatRecordTime(value: string, received: boolean) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const dateTime = `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
  return received ? `${dateTime} 接收` : dateTime;
}

export function formatDateTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString('zh-CN', { hour12: false });
}
