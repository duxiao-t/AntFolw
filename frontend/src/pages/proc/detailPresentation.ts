export type ProcessNode = {
  id: string;
  type: string;
  name?: string;
  label?: string;
  props?: Record<string, any>;
  children?: ProcessNode | null;
  branchs?: ProcessNode[];
};

export type NodeInstanceView = {
  id: number;
  nodeId: string;
  nodeType: string;
  roundNo: number;
  attemptNo: number;
  gatewayNodeInstanceId?: number | null;
  branchId?: string | null;
  status: string;
  startedAt?: string | null;
  completedAt?: string | null;
};

export type ApprovalRecordView = {
  id: string;
  taskId?: number | null;
  nodeId: string;
  nodeName: string;
  recordKind: string;
  nodeType?: string | null;
  parallelId?: string | null;
  branchId?: string | null;
  operationKind?: string | null;
  sourceOperatorName?: string | null;
  status: string;
  operatorName?: string | null;
  employeeNo?: string | null;
  department?: string | null;
  comment?: string | null;
  receivedAt: string;
  completedAt?: string | null;
  roundNo?: number | null;
};

export type HistoryView = {
  id: number;
  taskId?: number | null;
  fromNodeId?: string | null;
  toNodeId?: string | null;
  action: string;
  operatorId?: number | null;
  comment?: string | null;
  createdAt: string;
};

export type PersonView = {
  id: number;
  displayName?: string | null;
  username?: string | null;
  department?: string | null;
  employeeNo?: string | null;
};

export type ProgressStage = {
  id: string;
  label: string;
  detail: string;
  state: 'done' | 'current' | 'waiting' | 'error';
};

export type ActivityCategory = 'approval' | 'cc' | 'system';

export type ActivityItem = {
  id: string;
  category: ActivityCategory;
  title: string;
  status: string;
  state: 'done' | 'current' | 'error' | 'neutral';
  person: string;
  identity?: string;
  source?: string;
  comment: string;
  time: string;
  roundNo: number;
  context?: string;
};

export type InstancePresentation = {
  visibility: string;
  id: number;
  status: string;
  formName?: string | null;
  businessNo?: string | null;
  applicantName?: string | null;
  applicantEmployeeNo?: string | null;
  applicantDepartment?: string | null;
  startedAt?: string | null;
  currentNodeName?: string | null;
  approvalRecords?: ApprovalRecordView[] | null;
  history?: HistoryView[] | null;
};

const BUSINESS_NODE_TYPES = new Set(['APPROVAL', 'CC', 'DELAY', 'TRIGGER']);

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

export function parseProcessSnapshot(value: unknown): ProcessNode | null {
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value);
      return parsed && typeof parsed === 'object' ? parsed as ProcessNode : null;
    } catch {
      return null;
    }
  }
  return value && typeof value === 'object' ? value as ProcessNode : null;
}

export function instanceStatus(status: string, currentNodeId?: string | null) {
  if (currentNodeId === '__rework__') return { label: '待修改', tone: 'warning' as const };
  return ({
    RUNNING: { label: '审批中', tone: 'processing' as const },
    APPROVED: { label: '已通过', tone: 'success' as const },
    REJECTED: { label: '已驳回', tone: 'error' as const },
    WITHDRAWN: { label: '已撤回', tone: 'default' as const },
  } as const)[status] ?? { label: status || '状态未知', tone: 'default' as const };
}

export function formatDateTime(value?: string | null) {
  if (!value) return '未记录';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  });
}

export function formatDuration(start?: string | null, end?: string | null, now = Date.now()) {
  if (!start) return '未记录';
  const startedAt = Date.parse(start);
  const finishedAt = end ? Date.parse(end) : now;
  if (!Number.isFinite(startedAt) || !Number.isFinite(finishedAt) || finishedAt < startedAt) {
    return '未记录';
  }
  let seconds = Math.floor((finishedAt - startedAt) / 1000);
  const days = Math.floor(seconds / 86400);
  seconds %= 86400;
  const hours = Math.floor(seconds / 3600);
  seconds %= 3600;
  const minutes = Math.floor(seconds / 60);
  if (days > 0) return `${days}天 ${hours}小时`;
  if (hours > 0) return `${hours}小时 ${minutes}分钟`;
  if (minutes > 0) return `${minutes}分钟`;
  return `${seconds}秒`;
}

export function buildProgressStages({
  root,
  nodeInstances = [],
  approvalRecords = [],
  instanceStatus: status,
  currentNodeId,
  currentNodeName,
}: {
  root: ProcessNode | null;
  nodeInstances?: NodeInstanceView[];
  approvalRecords?: ApprovalRecordView[];
  instanceStatus: string;
  currentNodeId?: string | null;
  currentNodeName?: string | null;
}): ProgressStage[] {
  const stages: ProgressStage[] = [{
    id: '__start__',
    label: root ? nodeName(root, '发起申请') : '发起申请',
    detail: '表单已提交',
    state: 'done',
  }];
  const nodes: ProcessNode[] = [];
  collectLinearStages(root?.children ?? null, nodes);
  const latestInstances = latestNodeInstances(nodeInstances);
  for (const node of nodes) {
    const instance = latestInstances.get(node.id);
    const records = approvalRecords.filter((record) => record.nodeId === node.id);
    const state = progressState(
      instance?.status,
      records,
      status,
      status === 'RUNNING' && currentNodeId === node.id,
    );
    stages.push({
      id: node.id,
      label: nodeName(node, nodeTypeLabel(node.type)),
      detail: gatewayDetail(node, nodeInstances) || progressDetail(instance?.status, records),
      state,
    });
  }
  if (currentNodeId === '__rework__') {
    stages.push({ id: '__rework__', label: '修改后重提', detail: '等待发起人修改原单', state: 'current' });
  } else if (nodes.length === 0 && currentNodeId && status === 'RUNNING') {
    stages.push({
      id: currentNodeId,
      label: currentNodeName || nodeLabel(root, currentNodeId),
      detail: '当前处理节点',
      state: 'current',
    });
  }
  const terminal = status === 'APPROVED'
    ? { label: '流程完成', detail: '全部节点已完成', state: 'done' as const }
    : status === 'REJECTED'
      ? { label: '流程已驳回', detail: '流程已终止', state: 'error' as const }
      : status === 'WITHDRAWN'
        ? { label: '流程已撤回', detail: '等待后续处理', state: 'error' as const }
        : { label: '流程完成', detail: '等待后续节点', state: 'waiting' as const };
  stages.push({ id: '__end__', ...terminal });
  return stages;
}

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

export function nodeLabel(root: ProcessNode | null, id?: string | null) {
  if (!id) return '未记录节点';
  const node = findNode(root, id);
  return nodeName(node, id);
}

function collectLinearStages(node: ProcessNode | null, output: ProcessNode[]) {
  if (!node) return;
  if (BUSINESS_NODE_TYPES.has(node.type) || node.type === 'PARALLEL' || node.type === 'CONDITIONS') {
    output.push(node);
  }
  collectLinearStages(node.children ?? null, output);
}

function latestNodeInstances(values: NodeInstanceView[]) {
  const latest = new Map<string, NodeInstanceView>();
  for (const value of values) {
    const previous = latest.get(value.nodeId);
    if (!previous || value.roundNo > previous.roundNo
      || (value.roundNo === previous.roundNo && value.attemptNo >= previous.attemptNo)) {
      latest.set(value.nodeId, value);
    }
  }
  return latest;
}

function progressState(
  nodeStatus: string | undefined,
  records: ApprovalRecordView[],
  instanceStatusValue: string,
  current: boolean,
): ProgressStage['state'] {
  if (nodeStatus === 'ACTIVE' || current) return 'current';
  if (nodeStatus === 'REJECTED' || nodeStatus === 'CANCELLED') return 'error';
  if (nodeStatus === 'PASSED' || nodeStatus === 'AUTO_PASSED') return 'done';
  if (records.some((record) => ['PROCESSING', 'RETURNED'].includes(record.status))) return 'current';
  if (records.some((record) => record.status === 'REJECTED')) return 'error';
  if (records.length > 0) return 'done';
  return instanceStatusValue === 'APPROVED' ? 'done' : 'waiting';
}

function progressDetail(nodeStatus?: string, records: ApprovalRecordView[] = []) {
  if (nodeStatus === 'AUTO_PASSED') return '系统自动通过';
  if (nodeStatus === 'ACTIVE' || records.some((record) => record.status === 'PROCESSING')) return '正在处理';
  if (nodeStatus === 'REJECTED' || records.some((record) => record.status === 'REJECTED')) return '节点已驳回';
  if (nodeStatus === 'PASSED' || records.length > 0) return '节点已完成';
  return '等待到达';
}

function gatewayDetail(node: ProcessNode, instances: NodeInstanceView[]) {
  if (node.type !== 'PARALLEL' && node.type !== 'CONDITIONS') return '';
  const enteredIds = new Set(instances.map((instance) => instance.nodeId));
  const branches = node.branchs ?? [];
  const enteredBranches = branches.filter((branch) => descendants(branch)
    .some((descendant) => enteredIds.has(descendant.id)));
  if (node.type === 'CONDITIONS') {
    const selected = enteredBranches[0];
    return selected ? `已进入：${nodeName(selected, '匹配分支')}` : '等待条件判断';
  }
  const business = enteredBranches.flatMap(descendants)
    .filter((descendant) => BUSINESS_NODE_TYPES.has(descendant.type));
  const completed = business.filter((descendant) => instances.some((instance) =>
    instance.nodeId === descendant.id && ['PASSED', 'AUTO_PASSED'].includes(instance.status))).length;
  const branchCount = enteredBranches.length || branches.length;
  return business.length > 0
    ? `${branchCount} 条分支 · ${completed}/${business.length} 个节点完成`
    : `${branchCount} 条并行分支`;
}

function descendants(node: ProcessNode): ProcessNode[] {
  return [node, ...(node.branchs ?? []).flatMap(descendants),
    ...(node.children ? descendants(node.children) : [])];
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

function flowContext(root: ProcessNode | null, nodeId: string) {
  const path: ProcessNode[] = [];
  if (!findPath(root, nodeId, path)) return undefined;
  const labels: string[] = [];
  for (let index = 0; index < path.length - 1; index++) {
    const parent = path[index];
    const child = path[index + 1];
    if (parent?.type === 'PARALLEL' && child?.type === 'BRANCH') {
      labels.push(nodeName(child, '并行分支'));
    }
    if (parent?.type === 'CONDITIONS' && child?.type === 'CONDITION') {
      labels.push(`条件：${child.props?.isDefault === true ? '其他情况' : nodeName(child, '已匹配')}`);
    }
  }
  return labels.join(' · ') || undefined;
}

function findNode(node: ProcessNode | null, id: string): ProcessNode | null {
  if (!node) return null;
  if (node.id === id) return node;
  for (const branch of node.branchs ?? []) {
    const found = findNode(branch, id);
    if (found) return found;
  }
  return findNode(node.children ?? null, id);
}

function findPath(node: ProcessNode | null, id: string, path: ProcessNode[]): boolean {
  if (!node) return false;
  path.push(node);
  if (node.id === id) return true;
  for (const branch of node.branchs ?? []) {
    if (findPath(branch, id, path)) return true;
  }
  if (findPath(node.children ?? null, id, path)) return true;
  path.pop();
  return false;
}

function nodeName(node: ProcessNode | null, fallback: string) {
  const values = [node?.name, node?.props?.name, node?.props?.title, node?.label];
  const name = values.find((value) => typeof value === 'string' && value.trim());
  return typeof name === 'string' ? name : fallback;
}

function nodeTypeLabel(type: string) {
  return ({ APPROVAL: '审批节点', CC: '抄送节点', DELAY: '延时节点', TRIGGER: '自动任务',
    PARALLEL: '并行处理', CONDITIONS: '条件分支' } as Record<string, string>)[type] ?? type;
}

function uniqueText(values: Array<string | null | undefined>) {
  return [...new Set(values.flatMap((value) => value?.trim() ? [value.trim()] : []))];
}

function safeTime(value?: string | null) {
  const time = value ? Date.parse(value) : 0;
  return Number.isFinite(time) ? time : 0;
}
