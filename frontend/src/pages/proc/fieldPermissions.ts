import type { FieldMode } from '../../registry/types';

/** 只保留当前节点允许编辑的字段值，其余字段不随审批提交。 */
export function pickEditableValues(
  values: Record<string, any>,
  modes: Record<string, FieldMode>,
): Record<string, any> {
  return Object.fromEntries(
    Object.entries(values ?? {}).filter(([fieldId]) => modes[fieldId] === 'runtime-fill'),
  );
}
