// 流程快照的解析与树导航。进度轨和轨迹都要「按 nodeId 找节点、取中文名」，
// 所以单独一层，避免两个构建器互相 import 成环。
import type { ProcessNode } from './detailTypes';

/** 业务节点：进度轨只把这些类型（外加两种网关）当作可见阶段。 */
export const BUSINESS_NODE_TYPES = new Set(['APPROVAL', 'CC', 'DELAY', 'TRIGGER']);

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

export function nodeLabel(root: ProcessNode | null, id?: string | null) {
  if (!id) return '未记录节点';
  // 「驳回待改」不是真节点：`current_node_id` 存的是哨兵，直接展示会看到 `__rework__`。
  // 文案与后端 ProcessTreeNav.displayNameFromSnapshot 对齐。
  if (id === '__rework__') return '待修改';
  const node = findNode(root, id);
  return nodeName(node, id);
}

export function findNode(node: ProcessNode | null, id: string): ProcessNode | null {
  if (!node) return null;
  if (node.id === id) return node;
  for (const branch of node.branchs ?? []) {
    const found = findNode(branch, id);
    if (found) return found;
  }
  return findNode(node.children ?? null, id);
}

export function findPath(node: ProcessNode | null, id: string, path: ProcessNode[]): boolean {
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

/** 节点所在的分支路径（仅并行分支名 / 条件分支名），用于轨迹里补上下文。 */
export function flowContext(root: ProcessNode | null, nodeId: string) {
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

export function nodeName(node: ProcessNode | null, fallback: string) {
  const values = [node?.name, node?.props?.name, node?.props?.title, node?.label];
  const name = values.find((value) => typeof value === 'string' && value.trim());
  return typeof name === 'string' ? name : fallback;
}

export function nodeTypeLabel(type: string) {
  return ({ APPROVAL: '审批节点', CC: '抄送节点', DELAY: '延时节点', TRIGGER: '自动任务',
    PARALLEL: '并行处理', CONDITIONS: '条件分支' } as Record<string, string>)[type] ?? type;
}
