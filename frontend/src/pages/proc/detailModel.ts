import type { FieldMode } from '../../registry/types';
import type { ProcessNode } from './detailPresentation';

/** 页面上 6 个能力位，只在壳层算一次，子组件只读。 */
export type DetailCapabilities = {
  approve: boolean;
  reject: boolean;
  withdraw: boolean;
  discard: boolean;
  override: boolean;
  retryAutomation: boolean;
};

/** 服务端把 schema/formData 存成 JSON 字符串，这里统一兜住解析失败。 */
export function parseJsonValue<T>(value: unknown, fallback: T): T {
  if (!value) return fallback;
  if (typeof value !== 'string') return value as T;
  try {
    return JSON.parse(value) as T;
  } catch {
    return fallback;
  }
}

export function findNodeById(node: ProcessNode | null | undefined, id: string): ProcessNode | null {
  if (!node || typeof node !== 'object' || !node.id) return null;
  if (node.id === id) return node;
  if (Array.isArray(node.branchs)) {
    for (const branch of node.branchs) {
      const hit = findNodeById(branch, id);
      if (hit) return hit;
    }
  }
  return findNodeById(node.children, id);
}

// 并行分支节点需要 parallelId/branchId 上下文，不能直接作为重建入口。
export function findApproverNodes(
  node: ProcessNode | null | undefined,
  acc: Array<{ id: string; name: string }> = [],
  insideParallelBranch = false,
): Array<{ id: string; name: string }> {
  if (!node) return acc;
  if (node.type === 'APPROVAL' && !insideParallelBranch) {
    acc.push({ id: node.id, name: node.name ?? node.id });
  }
  if (node.children) {
    findApproverNodes(node.children, acc, insideParallelBranch);
  }
  if (Array.isArray(node.branchs)) {
    const branchContext = insideParallelBranch || node.type === 'PARALLEL';
    for (const branch of node.branchs) {
      findApproverNodes(branch, acc, branchContext);
    }
  }
  return acc;
}

/**
 * 字段模式：发起人视角取 ROOT 的 props.formPerms —— 制单人在 ROOT 没有待办任务，
 * 只按待办节点取会得到空集，ROOT 配的 HIDDEN 在详情里形同虚设（返工/待修改期间也一样，
 * 待办任务本身被 taskType === 'REWORK' 过滤掉了）。文案/口径与后端 starterFieldModes 一致。
 * 注意发起人视角**保持只读**：ROOT 的 EDITABLE 不是"当前可编辑"，别让它冒出编辑控件。
 */
export function buildFieldModes({
  snapshot,
  pendingNodeId,
  currentUserId,
  startedBy,
}: {
  snapshot: ProcessNode | null;
  pendingNodeId?: string | null;
  currentUserId?: number;
  startedBy?: number;
}): Record<string, FieldMode> {
  const modes: Record<string, FieldMode> = {};
  if (!snapshot) return modes;
  const starter = currentUserId != null && startedBy === currentUserId;
  if (starter) {
    for (const entry of snapshot.props?.formPerms ?? []) {
      modes[entry.fieldId] = entry.mode === 'HIDDEN' ? 'hidden' : 'readonly';
    }
  }
  if (!pendingNodeId) return modes;
  const node = findNodeById(snapshot, pendingNodeId);
  for (const entry of node?.props?.formPerms ?? []) {
    if (entry.mode === 'HIDDEN') modes[entry.fieldId] = 'hidden';
    else if (entry.mode === 'EDITABLE') modes[entry.fieldId] = 'runtime-fill';
    else modes[entry.fieldId] = 'readonly';
  }
  return modes;
}
