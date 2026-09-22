export type FormDataFieldValue = {
  fieldId: string;
  fieldName: string;
  value: unknown;
};

/** 上传件带 contentUrl（或 url）；检查项之类的对象数组也有 name，不能只看 name。 */
function isAttachment(item: unknown): boolean {
  return Boolean(item) && typeof item === 'object' && ('contentUrl' in (item as object) || 'url' in (item as object));
}

/**
 * 台账单元格里的一句话。
 *
 * 这个接口只回 fieldId/fieldName/value，**不给字段类型**，所以只能按值的形状判断：
 * 字符串数组当多选、带 contentUrl 的当附件、其余数组给个中性数量。形状判断不完美，
 * 但比把整坨 JSON 塞进单元格好得多；要更准就得让后端带上 schema/类型。
 */
export function cellText(value: unknown): string {
  if (value == null || value === '') return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  if (Array.isArray(value)) {
    if (value.length === 0) return '';
    if (value.every((item) => typeof item === 'string')) return value.join('、');
    if (value.every(isAttachment)) return `${value.length} 个附件`;
    return `${value.length} 项`;
  }
  if (typeof value === 'object') {
    const count = Object.keys(value).length;
    return count ? `${count} 项` : '';
  }
  return String(value);
}

/** 详情抽屉里的一项。展开来看，所以不再压缩成一句话。 */
export function detailText(value: unknown): string {
  if (value == null || value === '') return '—';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  if (Array.isArray(value)) {
    if (value.length === 0) return '—';
    if (value.every((item) => typeof item === 'string')) return value.join('、');
    if (value.every(isAttachment)) {
      return (value as Array<{ name?: unknown }>).map((item) => String(item.name ?? '')).join('\n');
    }
    return value.map((item) => JSON.stringify(item)).join('\n');
  }
  return JSON.stringify(value, null, 2) ?? String(value);
}

/** 按首次出现顺序取并集：本页出现过哪些字段，台账就有哪些列。 */
export function fieldColumns(fields: FormDataFieldValue[][]): Array<{ id: string; label: string }> {
  const seen = new Map<string, string>();
  fields.forEach((list) => {
    list.forEach((field) => {
      if (!field.fieldId || seen.has(field.fieldId)) return;
      seen.set(field.fieldId, field.fieldName || field.fieldId);
    });
  });
  return [...seen.entries()].map(([id, label]) => ({ id, label }));
}
