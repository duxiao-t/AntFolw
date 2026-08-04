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
 * - 日期组件勾选"默认当前时间"时取当前时间；
 * - 否则返回配置的自定义默认值字符串。
 */
export function schemaDefaultValue(node: MobileSchemaNode): unknown {
  const props = node.props ?? {};
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
  return typeof value === 'string' && value.trim() !== '' ? value : undefined;
}

/**
 * 用表单 schema 的默认值填充尚未填写的字段（草稿/重提的值优先）。
 */
export function applySchemaDefaults(
  nodes: MobileSchemaNode[],
  values: MobileFormValues,
): MobileFormValues {
  const next: MobileFormValues = { ...values };
  const visit = (list: MobileSchemaNode[]) => {
    for (const node of list) {
      if (node.type === 'table_list') {
        continue; // 明细表行内默认值由字段组件自己处理
      }
      const current = next[node.id];
      const isEmpty = current == null || current === '';
      if (isEmpty) {
        const def = schemaDefaultValue(node);
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
