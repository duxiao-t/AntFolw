import type { MobileFormValues, MobileSchemaNode } from './types';

/**
 * 格式化日期时间为字段保存的字符串格式（YYYY / MM / DD / HH / mm）。
 * 移动端不依赖 dayjs，保持轻量。
 */
export function formatDateTime(date: Date, format: string): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  const tokens: Record<string, string> = {
    YYYY: String(date.getFullYear()),
    MM: pad(date.getMonth() + 1),
    DD: pad(date.getDate()),
    HH: pad(date.getHours()),
    mm: pad(date.getMinutes()),
  };
  return format.replace(/YYYY|MM|DD|HH|mm/g, (token) => tokens[token] ?? token);
}

/**
 * 计算字段在填报时的默认值：
 * - 用户/部门选择勾选"默认填充当前用户/部门"时取当前登录人的 id；
 * - 日期组件勾选"默认当前时间"时取当前时间；
 * - 否则返回配置的自定义默认值字符串。
 */
export function schemaDefaultValue(
  node: MobileSchemaNode,
  current?: { userId?: number; deptId?: number },
): unknown {
  const props = node.props ?? {};
  if (props.defaultToCurrent === true) {
    const id = node.type === 'dept_picker' ? current?.deptId : current?.userId;
    if (id == null) return undefined;
    // 多选也只填一个：maxCount 至少是 1，不会越界。
    return props.multiple === true ? [id] : id;
  }
  if (props.defaultNow === true) {
    if (node.type === 'date') {
      const format = typeof props.format === 'string' && props.format
        ? props.format
        : 'YYYY-MM-DD';
      return formatDateTime(new Date(), format);
    }
    return undefined;
  }
  const value = props.defaultValue;
  if (node.type === 'multi_select') {
    return Array.isArray(value)
      ? value.filter((item) => typeof item === 'string' || typeof item === 'number')
      : undefined;
  }
  if (node.type === 'select' && (typeof value === 'string' || typeof value === 'number')) {
    return typeof value === 'string' && value.trim() === '' ? undefined : value;
  }
  return typeof value === 'string' && value.trim() !== '' ? value : undefined;
}

/**
 * 用表单 schema 的默认值填充尚未填写的字段（草稿/重提的值优先）。
 */
export function applySchemaDefaults(
  nodes: MobileSchemaNode[],
  values: MobileFormValues,
  current?: { userId?: number; deptId?: number },
): MobileFormValues {
  const next: MobileFormValues = { ...values };
  const visit = (list: MobileSchemaNode[]) => {
    for (const node of list) {
      if (node.type === 'table_list') {
        continue; // 明细表行内默认值由字段组件自己处理
      }
      const currentValue = next[node.id];
      const isEmpty = currentValue == null || currentValue === '';
      if (isEmpty) {
        const def = schemaDefaultValue(node, current);
        if (def !== undefined && def !== '') {
          next[node.id] = def;
        }
      }
      if (Array.isArray(node.children) && node.children.length > 0) {
        visit(node.children);
      }
    }
  };
  visit(nodes);
  return next;
}
