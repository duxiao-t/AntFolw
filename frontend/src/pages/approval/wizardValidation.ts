import { formRegistry } from '../../registry/formRegistry';
import type { SchemaNode } from '../../registry/types';
import { isBoundOptionSource } from '../../components/form-fields/dynamicOptions';
import type { PublishCheckItem } from './wizardTypes';

export const WIZARD_STEPS = [
  { key: 'basic', title: '表单属性' },
  { key: 'designer', title: '表单制作' },
  { key: 'process', title: '流程设计' },
  { key: 'publish', title: '预览发布' },
];

export function parseJsonValue<T>(value: T | string | undefined, fallback: T): T {
  if (typeof value !== 'string') return value ?? fallback;
  try {
    return JSON.parse(value);
  } catch {
    return fallback;
  }
}

export function getWorkflowEnabled(
  settings: Record<string, any> | string | undefined,
) {
  return !!parseJsonValue<Record<string, any>>(settings, {}).workflowEnabled;
}

function getNodeLabel(node: SchemaNode) {
  return node.label || formRegistry[node.type]?.label || node.type;
}

export function grantDepartmentTree(departments: { id: number; parentId?: number; name: string }[]) {
  const children = new Map<number | null, typeof departments>();
  departments.forEach((department) => {
    const parentId = department.parentId ?? null;
    children.set(parentId, [...(children.get(parentId) ?? []), department]);
  });
  const build = (parentId: number | null): any[] => (children.get(parentId) ?? []).map((department) => ({
    value: department.id,
    title: department.name,
    children: build(department.id),
  }));
  return build(null);
}

export function enrichSchemaLabels(nodes: SchemaNode[]): SchemaNode[] {
  return nodes.map((node) => ({
    ...node,
    label: getNodeLabel(node),
    children: node.children ? enrichSchemaLabels(node.children) : undefined,
  }));
}

export function collectOptionErrors(nodes: SchemaNode[]) {
  const optionTypes = new Set(['select', 'multi_select']);
  const errors: string[] = [];
  nodes.forEach((node) => {
    if (optionTypes.has(node.type)) {
      if (isBoundOptionSource(node.props)) {
        // 已绑定外部数据源但值列/显示列没选全（多列数据源需手动选择）。
        // 发布时后端会拒绝，这里提前告诉管理员，避免只看到一个「选项列映射不存在」。
        const source = node.props?.optionSource as { valueColumn?: string; labelColumn?: string } | undefined;
        if (!source?.valueColumn?.trim() || !source?.labelColumn?.trim()) {
          errors.push(getNodeLabel(node));
        }
      } else if (!Array.isArray(node.props?.options) || node.props.options.length === 0) {
        errors.push(getNodeLabel(node));
      }
    }
    if (node.children) {
      errors.push(...collectOptionErrors(node.children));
    }
  });
  return errors;
}

export function collectUploadWarnings(nodes: SchemaNode[]) {
  const warnings: string[] = [];
  nodes.forEach((node) => {
    if (node.type === 'file_upload' && !node.props?.accept) {
      warnings.push(getNodeLabel(node));
    }
    if (node.children) {
      warnings.push(...collectUploadWarnings(node.children));
    }
  });
  return warnings;
}

export function hasApprovalNode(node: any): boolean {
  if (!node) return false;
  if (node.type === 'APPROVAL') return true;
  if (hasApprovalNode(node.children)) return true;
  return Array.isArray(node.branchs) && node.branchs.some(hasApprovalNode);
}

export function getSteps(workflowEnabled: boolean) {
  return WIZARD_STEPS.filter((item) => workflowEnabled || item.key !== 'process');
}

export function stepFromSearch(search: string, steps: typeof WIZARD_STEPS) {
  const key = new URLSearchParams(search).get('step') ?? 'basic';
  const index = steps.findIndex((item) => item.key === key);
  return index >= 0 ? index : 0;
}

type PublishCheckInput = {
  definition?: AntFlowAPI.FormDefinition;
  schemaLength: number;
  optionErrors: string[];
  uploadWarnings: string[];
  workflowEnabled: boolean;
  processDefinitionId?: number;
  approvalNodeReady: boolean;
  processIssues: { message: string }[];
};

/** 发布检查项。顺序即发布页展示顺序，也是 `publishErrors[0]` 的取值依据。 */
export function buildPublishChecks({
  definition,
  schemaLength,
  optionErrors,
  uploadWarnings,
  workflowEnabled,
  processDefinitionId,
  approvalNodeReady,
  processIssues,
}: PublishCheckInput): PublishCheckItem[] {
  const checks: PublishCheckItem[] = [
    {
      key: 'definition',
      status: definition ? 'success' : 'error',
      title: '表单定义已保存',
      description: definition ? '已读取当前草稿配置' : '请先保存表单属性',
    },
    {
      key: 'name',
      status: definition?.name?.trim() ? 'success' : 'error',
      title: '表单名称已填写',
      description: definition?.name?.trim() || '表单名称不能为空',
    },
    {
      key: 'schema',
      status: schemaLength > 0 ? 'success' : 'error',
      title: '至少包含 1 个组件',
      description:
        schemaLength > 0
          ? `当前共 ${schemaLength} 个组件`
          : '请返回表单制作添加组件',
    },
    {
      key: 'options',
      status: optionErrors.length === 0 ? 'success' : 'error',
      title: '选项类组件配置完整',
      description:
        optionErrors.length === 0
          ? '单选/多选组件均已配置选项'
          : `${optionErrors.join('、')} 缺少选项`,
    },
    {
      key: 'upload',
      status: uploadWarnings.length === 0 ? 'success' : 'warning',
      title: '上传组件限制',
      description:
        uploadWarnings.length === 0
          ? '上传组件未发现明显风险'
          : `${uploadWarnings.join('、')} 未限制文件类型，发布后仍可使用`,
    },
  ];

  const workflowReady = !!processDefinitionId && approvalNodeReady && processIssues.length === 0;
  if (workflowEnabled) {
    checks.push({
      key: 'workflow',
      status: workflowReady ? 'success' : 'error',
      title: '审批流程已配置',
      description: workflowReady
        ? '已配置至少一个审批节点'
        : processIssues[0]?.message ?? '启用审批流程后，必须至少配置一个审批节点',
    });
  } else {
    checks.push({
      key: 'workflow',
      status: 'success',
      title: '提交后行为明确',
      description: '未启用审批流程，用户提交后直接完成',
    });
  }

  return checks;
}
