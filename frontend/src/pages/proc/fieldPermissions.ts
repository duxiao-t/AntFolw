import { collectVisibleValues } from '../../registry/displayConditions';
import type { FieldMode, SchemaNode } from '../../registry/types';

/** 只保留当前节点允许编辑的字段值，其余字段不随审批提交。 */
export function pickEditableValues(
  values: Record<string, any>,
  modes: Record<string, FieldMode>,
  schema?: SchemaNode[],
): Record<string, any> {
  const visibleValues = schema ? collectVisibleValues(schema, values) : values;
  return Object.fromEntries(
    Object.entries(visibleValues ?? {}).filter(([fieldId]) => modes[fieldId] === 'runtime-fill'),
  );
}
