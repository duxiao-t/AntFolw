// 进度轨（左侧竖排阶段条）的构建：把流程树 + 节点实例 + 审批记录压成 ProgressStage[]。
// 只读不写，与右侧轨迹互不依赖。
import type { ApprovalRecordView, NodeInstanceView, ProcessNode, ProgressStage } from './detailTypes';
import { BUSINESS_NODE_TYPES, nodeLabel, nodeName, nodeTypeLabel } from './detailTree';

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
