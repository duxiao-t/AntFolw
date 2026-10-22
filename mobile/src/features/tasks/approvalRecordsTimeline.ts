import type { MobileFlowNode, MobileSchemaNode } from '../forms/schema/types';
import { automationComment } from './approvalRecordLabels';
import {
  conditionContexts,
  findNode,
  nodeName,
  normalizeSnapshot,
  orderOf,
  parallelBranches,
  parallelContext,
} from './approvalRecordsFlow';
import type {
  ApprovalRecord,
  MobileHistoryItem,
} from './tasks.api';

export type TimelineRecord = ApprovalRecord & {
  conditionKeys: string[];
  conditionLabels: string[];
};
export type ParallelBranch = { id: string; label: string; records: TimelineRecord[] };
export type TimelineBlock =
  | { kind: 'round'; key: string; roundNo: number }
  | { kind: 'condition'; key: string; label: string }
  | { kind: 'record'; record: TimelineRecord }
  | { kind: 'parallel'; key: string; branches: ParallelBranch[] };

// 把审批记录 + 历史事件装配成时间线块：并行分支先分组，再插入轮次与条件分隔块。
export function buildTimeline(
  records: ApprovalRecord[],
  processSnapshot: unknown,
  schema: MobileSchemaNode[],
  history: MobileHistoryItem[],
): TimelineBlock[] {
  const root = normalizeSnapshot(processSnapshot);
  const enriched = mergeCc([...records, ...automationRecords(history, root)])
    .sort((a, b) => Date.parse(a.receivedAt) - Date.parse(b.receivedAt))
    .map((record) => enrichRecord(record, root, schema));
  const rawBlocks: Exclude<TimelineBlock, { kind: 'condition' | 'round' }>[] = [];
  for (let index = 0; index < enriched.length; ) {
    const record = enriched[index];
    if (!record) break;
    if (!record.parallelId) {
      rawBlocks.push({ kind: 'record', record });
      index++;
      continue;
    }
    const group: TimelineRecord[] = [];
    while (
      enriched[index]?.parallelId === record.parallelId &&
      (enriched[index]?.roundNo ?? 1) === (record.roundNo ?? 1)
    ) {
      const item = enriched[index++];
      if (item) group.push(item);
    }
    const branchNodes = parallelBranches(root, record.parallelId);
    const branchOrder = branchNodes.map((branch) => branch.id);
    const byBranch = new Map<string, TimelineRecord[]>();
    for (const item of group) {
      const key = item.branchId || 'default';
      byBranch.set(key, [...(byBranch.get(key) ?? []), item]);
    }
    const branches = [...byBranch.entries()]
      .sort(
        ([left], [right]) =>
          orderOf(branchOrder, left) - orderOf(branchOrder, right),
      )
      .map(([id, value], index) => ({
        id,
        label: nodeName(
          branchNodes.find((branch) => branch.id === id) ?? null,
          `分支 ${index + 1}`,
        ),
        records: value,
      }));
    rawBlocks.push({
      kind: 'parallel',
      key: `parallel-${record.parallelId}-${record.id}`,
      branches,
    });
  }
  const blocks: TimelineBlock[] = [];
  const showRounds = enriched.some((record) => (record.roundNo ?? 1) > 1);
  let previousRound = 0;
  let previousConditionKeys: string[] = [];
  for (const [blockIndex, block] of rawBlocks.entries()) {
    const first =
      block.kind === 'record' ? block.record : block.branches[0]?.records[0];
    const roundNo = first?.roundNo ?? 1;
    if (showRounds && roundNo !== previousRound) {
      blocks.push({ kind: 'round', key: `round-${roundNo}-${blockIndex}`, roundNo });
      previousConditionKeys = [];
    }
    const conditionKeys = first?.conditionKeys ?? [];
    const shared = commonPrefixLength(previousConditionKeys, conditionKeys);
    if (first) {
      first.conditionLabels.slice(shared).forEach((label, offset) => {
        const index = shared + offset;
        blocks.push({
          kind: 'condition',
          key: `condition-${first.conditionKeys[index]}-${blockIndex}`,
          label,
        });
      });
    }
    blocks.push(block);
    previousRound = roundNo;
    previousConditionKeys = conditionKeys;
  }
  return blocks;
}

function commonPrefixLength(left: string[], right: string[]) {
  let index = 0;
  while (index < left.length && left[index] === right[index]) index++;
  return index;
}

// 历史里的延时 / 自动任务事件合成"系统"记录；同节点的起止事件配对成一条。
function automationRecords(
  history: MobileHistoryItem[],
  root: MobileFlowNode | null,
): ApprovalRecord[] {
  const records: ApprovalRecord[] = [];
  const open = new Map<string, number>();
  for (const event of [...history].sort(
    (a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt),
  )) {
    const nodeId = event.toNodeId || event.fromNodeId;
    const node = nodeId ? findNode(root, nodeId) : null;
    const start =
      event.action === 'DELAY_SCHEDULED' || event.action === 'TRIGGER_QUEUED';
    const end =
      event.action === 'DELAY_COMPLETED' ||
      event.action === 'TRIGGER_SUCCEEDED' ||
      event.action === 'TRIGGER_FAILED';
    const autoPass = event.action === 'AUTO_PASS';
    if (!nodeId || (!start && !end && !autoPass)) continue;
    const context = parallelContext(root, nodeId);
    if (start || autoPass) {
      const record: ApprovalRecord = {
        id: `history-${event.id}`,
        nodeId,
        nodeName: nodeName(node, autoPass ? '自动通过' : '自动节点'),
        recordKind: 'AUTOMATION',
        nodeType: node?.type || (autoPass ? 'APPROVAL' : 'TRIGGER'),
        parallelId: context?.parallelId,
        branchId: context?.branchId,
        status: autoPass ? 'COMPLETED' : 'PROCESSING',
        operatorName: '系统',
        comment: autoPass
          ? '系统已自动通过该节点。'
          : automationComment(event.action),
        receivedAt: event.createdAt,
        completedAt: autoPass ? event.createdAt : null,
      };
      records.push(record);
      if (!autoPass) open.set(nodeId, records.length - 1);
      continue;
    }
    const existing = open.get(nodeId);
    if (existing != null) {
      const record = records[existing];
      if (record)
        records[existing] = {
          ...record,
          status: event.action === 'TRIGGER_FAILED' ? 'FAILED' : 'COMPLETED',
          comment: automationComment(event.action),
          completedAt: event.createdAt,
        };
      open.delete(nodeId);
    } else {
      records.push({
        id: `history-${event.id}`,
        nodeId,
        nodeName: nodeName(node, '自动节点'),
        recordKind: 'AUTOMATION',
        nodeType: node?.type || 'TRIGGER',
        parallelId: context?.parallelId,
        branchId: context?.branchId,
        status: event.action === 'TRIGGER_FAILED' ? 'FAILED' : 'COMPLETED',
        operatorName: '系统',
        comment: automationComment(event.action),
        receivedAt: event.createdAt,
        completedAt: event.createdAt,
      });
    }
  }
  return records;
}

// 合并同节点 / 同分支 / 同轮次的连续抄送记录（会原地改写入参数组的元素，调用方需先浅拷贝）。
function mergeCc(records: ApprovalRecord[]): ApprovalRecord[] {
  const merged: ApprovalRecord[] = [];
  for (const record of records.sort(
    (a, b) => Date.parse(a.receivedAt) - Date.parse(b.receivedAt),
  )) {
    const previous = merged.at(-1);
    if (
      record.recordKind === 'CC' &&
      previous?.recordKind === 'CC' &&
      record.nodeId === previous.nodeId &&
      record.parallelId === previous.parallelId &&
      record.branchId === previous.branchId
      && record.roundNo === previous.roundNo
    ) {
      previous.operatorName = [previous.operatorName, record.operatorName]
        .filter(Boolean)
        .join('、');
      previous.department =
        previous.department === record.department
          ? previous.department
          : `${previous.department || record.department || '未记录部门'}等`;
      previous.status =
        previous.status === 'APPROVED' && record.status === 'APPROVED'
          ? 'APPROVED'
          : 'PROCESSING';
      previous.completedAt =
        previous.status === 'APPROVED' ? record.completedAt : null;
      continue;
    }
    merged.push({ ...record });
  }
  return merged;
}

function enrichRecord(
  record: ApprovalRecord,
  root: MobileFlowNode | null,
  schema: MobileSchemaNode[],
): TimelineRecord {
  const node = record.nodeId ? findNode(root, record.nodeId) : null;
  const context = record.nodeId ? parallelContext(root, record.nodeId) : null;
  const conditions = record.nodeId
    ? conditionContexts(root, record.nodeId, schema)
    : [];
  return {
    ...record,
    nodeType: record.nodeType || node?.type,
    parallelId: record.parallelId || context?.parallelId,
    branchId: record.branchId || context?.branchId,
    conditionKeys: conditions.map((item) => item.key),
    conditionLabels: conditions.map((item) => item.label),
  };
}
