import { selectedOptionSummary, type SelectOption } from '@/components/form-fields/selectFieldShared';

export type FormDataFieldValue = {
  fieldId: string;
  fieldName: string;
  value: unknown;
};

/** 表单定义里一个字段的类型与字典。台账靠它把值显示成人看得懂的内容。 */
export type FieldMeta = {
  type: string;
  options: SelectOption[];
  /** 检查项的状态字典（通过 / 不通过 / 不适用） */
  results: Array<{ id: string; label: string }>;
  /** 检查项的条目字典 */
  items: Array<{ id: string; label: string }>;
};

/**
 * 从表单定义的 schema 抽「每个字段是什么类型、有哪些选项」。
 *
 * 数据接口只回 fieldId/fieldName/value，没有类型也没有选项，所以下拉只能显示原始值
 * （option_1），检查项只能显示一坨 JSON。schema 是唯一能补上这些的地方，会递归 children
 * （明细表里也有字段）。
 */
export function fieldMetas(schema: unknown): Map<string, FieldMeta> {
  const metas = new Map<string, FieldMeta>();
  const walk = (nodes: unknown) => {
    if (!Array.isArray(nodes)) return;
    nodes.forEach((node) => {
      if (!node || typeof node !== 'object') return;
      const record = node as Record<string, any>;
      const props = record.props ?? {};
      const id = typeof record.id === 'string' ? record.id : '';
      if (id) {
        metas.set(id, {
          type: String(record.type ?? ''),
          options: Array.isArray(props.options) ? props.options : [],
          results: Array.isArray(props.results) ? props.results : [],
          items: Array.isArray(props.items) ? props.items : [],
        });
      }
      walk(record.children);
    });
  };
  walk(schema);
  return metas;
}

function optionText(value: unknown, meta: FieldMeta) {
  const text = selectedOptionSummary(value, meta.options, meta.type === 'multi_select');
  return text === '未填写' ? '' : text;
}

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

/** 有字段类型时优先用类型：下拉给选项名、检查项给状态汇总；没有就退回按形状判断。 */
export function cellTextFor(value: unknown, meta?: FieldMeta): string {
  if (!meta) return cellText(value);
  if (meta.type === 'checklist' && Array.isArray(value)) {
    const counts = new Map<string, number>();
    value.forEach((row) => {
      const status = row && typeof row === 'object'
        ? String((row as Record<string, unknown>).status ?? '')
        : '';
      const label = meta.results.find((result) => result.id === status)?.label ?? '未填';
      counts.set(label, (counts.get(label) ?? 0) + 1);
    });
    return [...counts.entries()].map(([label, count]) => `${label} ${count}`).join(' · ');
  }
  if (meta.options.length && (meta.type === 'select' || meta.type === 'multi_select')) {
    return optionText(value, meta);
  }
  return cellText(value);
}

/** 详情抽屉里的一项；有字段类型时同样优先用类型。 */
export function detailTextFor(value: unknown, meta?: FieldMeta): string {
  if (!meta) return detailText(value);
  if (meta.type === 'checklist' && Array.isArray(value)) {
    return value.map((row) => {
      const item = (row ?? {}) as Record<string, unknown>;
      const id = String(item.id ?? '');
      const label = meta.items.find((entry) => entry.id === id)?.label
        ?? String(item.name ?? id);
      const status = meta.results.find((entry) => entry.id === String(item.status ?? ''))?.label
        ?? '未填';
      const images = Array.isArray(item.images) ? item.images.length : 0;
      return `${label}：${status}${images ? `（${images} 张图）` : ''}`;
    }).join('\n');
  }
  if (meta.options.length && (meta.type === 'select' || meta.type === 'multi_select')) {
    return optionText(value, meta) || '—';
  }
  return detailText(value);
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
