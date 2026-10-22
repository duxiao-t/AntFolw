// 流转轨迹（右侧时间线）的构建：审批记录 + 流程历史 → ActivityItem[]。
// 关键点：抄送记录按「节点+轮次+并行/分支」合并成一条，与审批记录重复的历史事件要去重，
// 合并/去重口径直接决定用户看到几条轨迹，改动前请先看 detailPresentation.test.ts。
import type {
  ActivityCategory,
  ActivityItem,
  ApprovalRecordView,
  HistoryView,
  PersonView,
  ProcessNode,
} from './detailTypes';
import { flowContext, nodeLabel } from './detailTree';

const ACTION_LABELS: Record<string, string> = {
  START: '发起流程',
  ARRIVE: '到达节点',
  APPROVE: '同意',
  REJECT: '驳回',
  REJECT_TO_NODE: '驳回到指定节点',
  WITHDRAW: '撤回流程',
  COMPLETE: '流程完成',
  CC: '抄送',
  AUTO_PASS: '自动通过',
  DELAY_SCHEDULED: '等待计时',
  DELAY_COMPLETED: '延时结束',
  TRIGGER_QUEUED: '自动任务已入队',
  TRIGGER_SUCCEEDED: '自动任务执行成功',
  TRIGGER_FAILED: '自动任务执行失败',
  FORCE_APPROVE: '紧急同意',
  FORCE_REJECT: '紧急驳回',
  TRANSFER: '转交',
  DELEGATE: '委派',
  ADD_ASSIGNEE: '加签',
  RECALL_APPROVAL: '追回审批',
  INVALIDATE: '审批作废',
  CANCEL: '任务取消',
  RESUBMIT: '重新提交',
};

export function buildActivityItems({
  root,
  approvalRecords = [],
  history = [],
  people = {},
  applicantName,
}: {
  root: ProcessNode | null;
  approvalRecords?: ApprovalRecordView[];
  history?: HistoryView[];
  people?: Record<number, PersonView>;
  applicantName?: string | null;
}): ActivityItem[] {
  const mergedRecords = mergeCcRecords(approvalRecords);
  const representedTaskIds = new Set(approvalRecords.flatMap((record) =>
    record.taskId == null ? [] : [record.taskId]));
  const approvalNodes = new Set(approvalRecords
    .filter((record) => record.recordKind !== 'CC')
    .map((record) => record.nodeId));
  const ccNodes = new Set(approvalRecords
    .filter((record) => record.recordKind === 'CC')
    .map((record) => record.nodeId));
  const items = mergedRecords.map((record) => recordActivity(record, root));
  for (const event of history) {
    if (historyCovered(event, representedTaskIds, approvalNodes, ccNodes, approvalRecords)) {
      continue;
    }
    items.push(historyActivity(event, root, people, applicantName));
  }
  return items.sort((left, right) => {
    const difference = safeTime(left.time) - safeTime(right.time);
    return difference || left.id.localeCompare(right.id);
  });
}

function recordActivity(record: ApprovalRecordView, root: ProcessNode | null): ActivityItem {
  const category: ActivityCategory = record.recordKind === 'CC'
    ? 'cc' : record.recordKind === 'APPROVAL' || record.recordKind === 'REWORK'
      ? 'approval' : 'system';
  const processing = ['PROCESSING', 'RETURNED'].includes(record.status);
  const rejected = record.status === 'REJECTED' || record.status === 'FAILED'
    || record.operationKind === 'INVALIDATED';
  const person = record.operatorName || (category === 'system' ? '系统' : '未记录人员');
  const identity = [record.department, record.employeeNo ? `工号 ${record.employeeNo}` : null]
    .filter(Boolean).join(' · ');
  return {
    id: record.id,
    category,
    title: record.nodeName || nodeLabel(root, record.nodeId),
    status: recordStatusLabel(record),
    state: rejected ? 'error' : processing ? 'current' : 'done',
    person,
    identity: identity || undefined,
    source: record.sourceOperatorName
      ? `${operationLabel(record.operationKind)}自 ${record.sourceOperatorName}` : undefined,
    comment: record.comment || defaultRecordComment(record),
    time: record.completedAt || record.receivedAt,
    roundNo: record.roundNo ?? 1,
    context: flowContext(root, record.nodeId),
  };
}

function historyActivity(
  event: HistoryView,
  root: ProcessNode | null,
  people: Record<number, PersonView>,
  applicantName?: string | null,
): ActivityItem {
  const nodeId = event.toNodeId || event.fromNodeId;
  const approvalAction = ['AUTO_PASS', 'FORCE_APPROVE', 'FORCE_REJECT', 'REJECT_TO_NODE',
    'TRANSFER', 'DELEGATE', 'ADD_ASSIGNEE', 'RECALL_APPROVAL', 'INVALIDATE'].includes(event.action);
  const error = ['REJECT', 'FORCE_REJECT', 'TRIGGER_FAILED', 'INVALIDATE', 'CANCEL'].includes(event.action);
  const actor = event.operatorId == null ? null : people[event.operatorId];
  const systemAction = ['AUTO_PASS', 'COMPLETE', 'DELAY_SCHEDULED', 'DELAY_COMPLETED',
    'TRIGGER_QUEUED', 'TRIGGER_SUCCEEDED', 'TRIGGER_FAILED', 'CC', 'ARRIVE'].includes(event.action);
  const person = systemAction
    ? '系统'
    : actor?.displayName || actor?.username
      || (event.action === 'WITHDRAW' || event.action === 'RESUBMIT' ? applicantName : null)
      || (event.operatorId ? `用户 #${event.operatorId}` : '系统');
  const identity = actor
    ? [actor.department, actor.employeeNo ? `工号 ${actor.employeeNo}` : null]
        .filter(Boolean).join(' · ')
    : '';
  return {
    id: `history-${event.id}`,
    category: approvalAction ? 'approval' : 'system',
    title: historyTitle(event, root, nodeId),
    status: ACTION_LABELS[event.action] ?? event.action,
    state: error ? 'error' : ['COMPLETE', 'AUTO_PASS', 'TRIGGER_SUCCEEDED', 'DELAY_COMPLETED'].includes(event.action)
      ? 'done' : 'neutral',
    person,
    identity: identity || undefined,
    comment: friendlyHistoryComment(event),
    time: event.createdAt,
    roundNo: 1,
    context: nodeId ? flowContext(root, nodeId) : undefined,
  };
}

function historyTitle(event: HistoryView, root: ProcessNode | null, nodeId?: string | null) {
  const flowTitles: Record<string, string> = {
    START: '提交申请',
    COMPLETE: '流程完成',
    WITHDRAW: '流程已撤回',
    RESUBMIT: '重新提交',
  };
  if (flowTitles[event.action]) return flowTitles[event.action];
  if (root && nodeId) return nodeLabel(root, nodeId);
  if (event.action === 'AUTO_PASS') return '自动审批节点';
  if (event.action.startsWith('DELAY_')) return '延时节点';
  if (event.action.startsWith('TRIGGER_')) return '自动任务';
  if (event.action === 'CC') return '抄送节点';
  if (event.action === 'ARRIVE') return '审批节点';
  return '流程状态更新';
}

/** 审批记录已经覆盖过的历史事件不再重复展示（否则一次同意会出现两条轨迹）。 */
function historyCovered(
  event: HistoryView,
  taskIds: Set<number>,
  approvalNodes: Set<string>,
  ccNodes: Set<string>,
  records: ApprovalRecordView[],
) {
  if (event.action === 'START' && records.some((record) => record.recordKind === 'SUBMISSION')) return true;
  if (['APPROVE', 'REJECT'].includes(event.action) && event.taskId != null && taskIds.has(event.taskId)) return true;
  if (event.action === 'ARRIVE' && event.toNodeId && approvalNodes.has(event.toNodeId)) return true;
  return event.action === 'CC' && event.toNodeId != null && ccNodes.has(event.toNodeId);
}

function mergeCcRecords(records: ApprovalRecordView[]) {
  const output: ApprovalRecordView[] = [];
  const indexes = new Map<string, number>();
  for (const record of records) {
    if (record.recordKind !== 'CC') {
      output.push({ ...record });
      continue;
    }
    const key = [record.nodeId, record.roundNo ?? 1, record.parallelId ?? '', record.branchId ?? ''].join(':');
    const index = indexes.get(key);
    if (index == null) {
      indexes.set(key, output.length);
      output.push({ ...record });
      continue;
    }
    const previous = output[index];
    if (!previous) continue;
    previous.operatorName = uniqueText([previous.operatorName, record.operatorName]).join('、');
    previous.department = previous.department === record.department
      ? previous.department : uniqueText([previous.department, record.department]).join('、');
    previous.employeeNo = uniqueText([previous.employeeNo, record.employeeNo]).join('、');
    if (record.status === 'PROCESSING') {
      previous.status = 'PROCESSING';
      previous.completedAt = null;
    } else if (previous.status !== 'PROCESSING' && safeTime(record.completedAt) > safeTime(previous.completedAt)) {
      previous.completedAt = record.completedAt;
    }
  }
  return output;
}

function recordStatusLabel(record: ApprovalRecordView) {
  if (record.recordKind === 'CC') return record.status === 'PROCESSING' ? '待查收' : '已抄送';
  if (record.operationKind === 'INVALIDATED') return '已作废';
  return ({
    SUBMITTED: '已提交',
    PROCESSING: '审批中',
    APPROVED: '已通过',
    REJECTED: '已驳回',
    RETURNED: '待修改',
    RESUBMITTED: '已重新提交',
    COMPLETED: '已完成',
    FAILED: '执行失败',
  } as Record<string, string>)[record.status] ?? record.status;
}

function defaultRecordComment(record: ApprovalRecordView) {
  if (record.recordKind === 'CC') return record.status === 'PROCESSING'
    ? '等待查收抄送内容。' : '已抄送相关人员。';
  return ({
    SUBMITTED: '已完成表单填写并提交审批。',
    PROCESSING: '等待处理当前审批节点。',
    APPROVED: '已完成本节点审批。',
    REJECTED: '本节点已驳回。',
    RETURNED: '等待发起人修改原单。',
    RESUBMITTED: '原单已修改并重新提交。',
  } as Record<string, string>)[record.status] ?? '节点状态已更新。';
}

function friendlyHistoryComment(event: HistoryView) {
  if (event.action === 'AUTO_PASS') {
    return event.comment === 'same approver policy'
      ? '审批人与发起人或上一节点相同，系统按流程规则自动通过。'
      : '系统按流程规则自动通过。';
  }
  if (event.comment && !['same approver policy', 'no assignee'].includes(event.comment)) {
    return event.comment;
  }
  return ({
    COMPLETE: '流程中的全部节点已完成。',
    WITHDRAW: '发起人撤回流程，原单进入待修改状态。',
    DELAY_SCHEDULED: '将在设定时间后自动继续。',
    DELAY_COMPLETED: '等待时间结束，流程继续流转。',
    TRIGGER_QUEUED: '自动任务等待执行。',
    TRIGGER_SUCCEEDED: '自动任务已执行成功。',
    TRIGGER_FAILED: '自动任务执行失败，请检查失败原因。',
    FORCE_APPROVE: '管理员紧急介入并同意。',
    FORCE_REJECT: '管理员紧急介入并驳回。',
    REJECT_TO_NODE: '流程已退回指定审批节点。',
    RECALL_APPROVAL: '已追回下游审批并返回当前节点。',
    CANCEL: '该任务已取消。',
  } as Record<string, string>)[event.action] ?? '流程状态已更新。';
}

function operationLabel(kind?: string | null) {
  return ({ TRANSFER: '转交', DELEGATE: '委派', ADD_ASSIGNEE: '加签' } as Record<string, string>)[kind ?? ''] ?? '操作';
}

function uniqueText(values: Array<string | null | undefined>) {
  return [...new Set(values.flatMap((value) => value?.trim() ? [value.trim()] : []))];
}

function safeTime(value?: string | null) {
  const time = value ? Date.parse(value) : 0;
  return Number.isFinite(time) ? time : 0;
}
