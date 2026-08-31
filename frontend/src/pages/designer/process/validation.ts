import type { TreeNode } from './types';

export type ProcessValidationIssue = {
  nodeId: string;
  message: string;
};

const hasConfiguredApproval = (node: TreeNode | null | undefined): boolean => {
  if (!node) return false;
  if (node.type === 'APPROVAL') return true;
  return (
    (node.branchs ?? []).some(hasConfiguredApproval) ||
    hasConfiguredApproval(node.children)
  );
};

const approvalReady = (node: TreeNode): boolean => {
  const props = node.props ?? {};
  if (props.assignedType === 'ASSIGN_USER') {
    return (props.assignedUser?.length ?? 0) > 0;
  }
  if (props.assignedType === 'ROLE') return (props.role?.length ?? 0) > 0;
  if (props.assignedType === 'DIRECT_MANAGER') {
    const level = Number(props.manager?.level);
    return Number.isInteger(level) && level >= 1 && level <= 10;
  }
  if (props.assignedType === 'FIELD_USER') {
    return Boolean(props.fieldUser?.fieldId);
  }
  return ['LEADER', 'SELF', 'SELF_SELECT'].includes(props.assignedType);
};

const approvalPolicyReady = (node: TreeNode): boolean => {
  const props = node.props ?? {};
  const mode = props.mode ?? 'OR';
  if (!['OR', 'AND', 'ANY', 'ALL', 'RATIO', 'SEQUENTIAL'].includes(mode)) {
    return false;
  }
  if (mode === 'RATIO' && !(Number(props.ratio) >= 1 && Number(props.ratio) <= 100)) {
    return false;
  }
  const timeout = props.timeoutPolicy;
  return (
    timeout == null ||
    (Number(timeout.afterMinutes) >= 1 &&
      Number(timeout.afterMinutes) <= 525600 &&
      ['REMIND', 'ESCALATE', 'AUTO_APPROVE'].includes(timeout.action) &&
      (timeout.action !== 'AUTO_APPROVE' || timeout.riskLevel === 'LOW'))
  );
};

const formPermsIssue = (node: TreeNode): string | null => {
  const perms = node.props?.formPerms;
  if (perms == null) return null;
  if (!Array.isArray(perms)) return '字段权限配置必须是数组';
  const seen = new Set<string>();
  for (const entry of perms) {
    if (!entry || typeof entry.fieldId !== 'string' || !entry.fieldId.trim()) {
      return '字段权限缺少字段 id';
    }
    if (!['HIDDEN', 'READONLY', 'EDITABLE'].includes(entry.mode)) {
      return `字段 ${entry.fieldId} 的权限模式非法`;
    }
    if (seen.has(entry.fieldId)) {
      return `字段 ${entry.fieldId} 重复配置权限`;
    }
    seen.add(entry.fieldId);
  }
  return null;
};

const conditionReady = (node: TreeNode): boolean => {
  if (node.props?.isDefault) return true;
  const groups = node.props?.groups ?? [];
  return (
    groups.length > 0 &&
    groups.every(
      (group: any) =>
        (group.conditions?.length ?? 0) > 0 &&
        group.conditions.every((condition: any) => {
          const valueReady =
            condition.operator === 'in'
              ? Array.isArray(condition.value) &&
                condition.value.length > 0 &&
                condition.value.every((value: unknown) =>
                  Boolean(String(value).trim()),
                )
              : condition.value !== undefined &&
                !Array.isArray(condition.value) &&
                String(condition.value).trim() !== '';
          return !!condition.field && !!condition.operator && valueReady;
        }),
    )
  );
};

const branchConditionMode = (node: TreeNode): string =>
  node.props?.conditionMode ?? 'ALWAYS';

const branchConditionReady = (node: TreeNode): boolean => {
  const mode = branchConditionMode(node);
  const groups = node.props?.groups;
  const emptyGroups =
    Array.isArray(groups) &&
    groups.every((group: any) => (group.conditions?.length ?? 0) === 0);
  return mode === 'ALWAYS' || (mode === 'WHEN_MATCHED' && emptyGroups);
};

const delayReady = (node: TreeNode): boolean => {
  const props = node.props ?? {};
  if (props.mode === 'UNTIL_TIME') {
    return /^([01]\d|2[0-3]):[0-5]\d$/.test(props.time ?? '');
  }
  const amount = Number(props.amount);
  const max =
    props.unit === 'DAYS' ? 365 : props.unit === 'HOURS' ? 8760 : 525600;
  return (
    ['MINUTES', 'HOURS', 'DAYS'].includes(props.unit) &&
    amount > 0 &&
    amount <= max
  );
};

const triggerReady = (node: TreeNode): boolean => {
  const props = node.props ?? {};
  let validUrl = false;
  try {
    const parsed = new URL(props.url ?? '');
    validUrl = parsed.protocol === 'http:' || parsed.protocol === 'https:';
  } catch {
    validUrl = false;
  }
  const rowsReady = (props.headers ?? []).every(
    (row: any) =>
      String(row.key ?? '').trim() && String(row.value ?? '').trim(),
  );
  const parametersReady = (props.parameters ?? []).every(
    (row: any) =>
      String(row.key ?? '').trim() &&
      (row.source === 'FIELD'
        ? String(row.fieldId ?? '').trim()
        : row.value !== undefined),
  );
  return (
    validUrl &&
    ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'].includes(props.method) &&
    ['application/json', 'application/x-www-form-urlencoded'].includes(
      props.contentType,
    ) &&
    ['ON_SUCCESS', 'AFTER_SEND'].includes(props.continueMode) &&
    String(props.secret ?? '').trim().length >= 8 &&
    rowsReady &&
    parametersReady
  );
};

export function validateProcessTree(root: TreeNode): ProcessValidationIssue[] {
  const issues: ProcessValidationIssue[] = [];
  const add = (nodeId: string, message: string) =>
    issues.push({ nodeId, message });

  const walk = (node: TreeNode | null | undefined): void => {
    if (!node) return;
    if (node.type === 'APPROVAL' && !approvalReady(node))
      add(node.id, '请配置审批人');
    if (node.type === 'APPROVAL') {
      if (!approvalPolicyReady(node)) add(node.id, '请配置有效的审批或超时规则');
      const permIssue = formPermsIssue(node);
      if (permIssue) add(node.id, permIssue);
    }
    if (node.type === 'CC' && (node.props?.assignedUser?.length ?? 0) === 0) {
      add(node.id, '请配置抄送人');
    }
    if (node.type === 'DELAY' && !delayReady(node))
      add(node.id, '请配置有效的延时规则');
    if (node.type === 'TRIGGER' && !triggerReady(node)) {
      add(node.id, '请完整配置 Webhook 地址、签名和参数');
    }
    if (node.type === 'CONDITIONS') {
      const branches = node.branchs ?? [];
      const defaults = branches.filter((branch) => branch.props?.isDefault);
      if (branches.length < 2) add(node.id, '条件节点至少需要两个分支');
      if (defaults.length !== 1)
        add(node.id, '条件节点必须且只能有一个默认分支');
      branches.forEach((branch) => {
        if (!conditionReady(branch)) add(branch.id, '请完整配置分支条件');
        walk(branch.children);
      });
    } else if (node.type === 'PARALLEL') {
      const branches = node.branchs ?? [];
      if (!['ALL', 'ANY'].includes(node.props?.joinMode ?? 'ALL')) {
        add(node.id, '请选择有效的并行汇聚方式');
      }
      if (branches.length < 2) add(node.id, '并行节点至少需要两个分支');
      branches.forEach((branch) => {
        if (!branchConditionReady(branch)) {
          add(branch.id, '并行分支必须始终执行');
        }
        if (!branch.children) add(branch.id, '并行分支不能为空');
        walk(branch.children);
      });
    }
    walk(node.children);
  };

  if (root.type !== 'ROOT') add(root.id, '流程必须从发起人节点开始');
  walk(root);
  if (!hasConfiguredApproval(root)) add(root.id, '流程至少需要一个审批节点');
  return issues;
}
