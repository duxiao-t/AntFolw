import type { MobileFlowNode, MobileSchemaNode } from '../forms/schema/types';

// 流程快照树的只读导航：审批时间线据此补齐并行分支、轮次与条件路径。
// 纯函数，不依赖 React；入参可以是后端返回的字符串快照。
export function normalizeSnapshot(value: unknown): MobileFlowNode | null {
  if (typeof value === 'string') {
    try {
      return JSON.parse(value) as MobileFlowNode;
    } catch {
      return null;
    }
  }
  return value && typeof value === 'object' ? (value as MobileFlowNode) : null;
}

export function findNode(
  node: MobileFlowNode | null,
  id: string,
): MobileFlowNode | null {
  if (!node) return null;
  if (node.id === id) return node;
  for (const branch of node.branchs ?? node.branches ?? []) {
    const hit = findNode(branch, id);
    if (hit) return hit;
  }
  const children = Array.isArray(node.children)
    ? node.children
    : node.children
      ? [node.children]
      : [];
  for (const child of children) {
    const hit = findNode(child, id);
    if (hit) return hit;
  }
  return null;
}

function findPath(
  node: MobileFlowNode | null,
  id: string,
  path: MobileFlowNode[],
): boolean {
  if (!node) return false;
  path.push(node);
  if (node.id === id) return true;
  for (const branch of node.branchs ?? node.branches ?? [])
    if (findPath(branch, id, path)) return true;
  const children = Array.isArray(node.children)
    ? node.children
    : node.children
      ? [node.children]
      : [];
  for (const child of children) if (findPath(child, id, path)) return true;
  path.pop();
  return false;
}

export function parallelContext(
  root: MobileFlowNode | null,
  nodeId: string,
  current: { parallelId: string; branchId?: string } | null = null,
): { parallelId: string; branchId?: string } | null {
  if (!root) return null;
  if (root.id === nodeId) return current;
  for (const branch of root.branchs ?? root.branches ?? []) {
    const context =
      root.type === 'PARALLEL'
        ? { parallelId: root.id, branchId: branch.id }
        : current;
    const hit = parallelContext(branch, nodeId, context);
    if (hit) return hit;
  }
  const children = Array.isArray(root.children)
    ? root.children
    : root.children
      ? [root.children]
      : [];
  for (const child of children) {
    const hit = parallelContext(child, nodeId, current);
    if (hit) return hit;
  }
  return null;
}

export function parallelBranches(
  root: MobileFlowNode | null,
  parallelId: string,
) {
  const parallel = findNode(root, parallelId);
  return parallel?.branchs ?? parallel?.branches ?? [];
}

export function orderOf(order: string[], id: string) {
  const index = order.indexOf(id);
  return index < 0 ? Number.MAX_SAFE_INTEGER : index;
}

export function nodeName(node: MobileFlowNode | null, fallback: string) {
  return (
    node?.name ||
    (typeof node?.props?.name === 'string' ? node.props.name : null) ||
    node?.label ||
    fallback
  );
}

function findSchemaNode(
  nodes: MobileSchemaNode[],
  id: string,
): MobileSchemaNode | null {
  for (const node of nodes) {
    if (node.id === id) return node;
    const child = findSchemaNode(node.children ?? [], id);
    if (child) return child;
  }
  return null;
}

// 只返回实际走过的条件分支（路径上的 CONDITIONS → CONDITION 相邻对）。
export function conditionContexts(
  root: MobileFlowNode | null,
  nodeId: string,
  schema: MobileSchemaNode[],
) {
  const path: MobileFlowNode[] = [];
  if (!findPath(root, nodeId, path)) return [];
  const result: Array<{ key: string; label: string }> = [];
  for (let index = 0; index < path.length - 1; index++) {
    const gateway = path[index];
    const branch = path[index + 1];
    if (gateway?.type === 'CONDITIONS' && branch?.type === 'CONDITION')
      result.push({
        key: `${gateway.id}:${branch.id}`,
        label: `条件判断：${conditionLabel(branch, schema)}`,
      });
  }
  return result;
}

function conditionLabel(branch: MobileFlowNode, schema: MobileSchemaNode[]) {
  if (branch.props?.isDefault === true) return '其他情况';
  const groups = Array.isArray(branch.props?.groups)
    ? (branch.props.groups as Array<Record<string, unknown>>)
    : [];
  const groupType = branch.props?.groupsType === 'AND' ? ' 且 ' : ' 或 ';
  const labels = groups
    .map((group) => {
      const conditions = Array.isArray(group.conditions)
        ? (group.conditions as Array<Record<string, unknown>>)
        : [];
      const joiner = group.groupType === 'OR' ? ' 或 ' : ' 且 ';
      return conditions
        .map((condition) => formatCondition(condition, schema))
        .join(joiner);
    })
    .filter(Boolean);
  return labels.join(groupType) || branch.name || '已命中';
}

function formatCondition(
  condition: Record<string, unknown>,
  schema: MobileSchemaNode[],
) {
  const field = findSchemaNode(schema, String(condition.field ?? ''));
  const operator =
    (
      {
        '==': '=',
        '!=': '≠',
        '>': '>',
        '>=': '≥',
        '<': '<',
        '<=': '≤',
        in: '属于',
        contains: '包含',
      } as Record<string, string>
    )[String(condition.operator)] ?? String(condition.operator ?? '');
  const values = Array.isArray(condition.value)
    ? condition.value
    : [condition.value];
  const options = Array.isArray(field?.props?.options)
    ? (field.props.options as Array<Record<string, unknown>>)
    : [];
  const formatted = values
    .map(
      (value) =>
        options.find((option) => option.value === value)?.label ?? value,
    )
    .join('、');
  const suffix =
    typeof field?.props?.suffix === 'string'
      ? field.props.suffix
      : typeof field?.props?.unit === 'string'
        ? field.props.unit
        : '';
  return `${field?.label || condition.field || '字段'} ${operator} ${formatted}${suffix}`.trim();
}
