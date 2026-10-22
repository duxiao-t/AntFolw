import { isHexColor } from '../../branding/brandTokens';
import type { MobileFileDto } from '../files.api';
import type { MobileSchemaNode } from '../schema/types';

export type ChecklistResultOption = { id: string; label: string; color: string };
export type ChecklistItemDef = { id: string; label: string; required: boolean };

export type ChecklistEntry = {
  id: string;
  name: string;
  status: string | null;
  description: string;
  images: MobileFileDto[];
};

export type ChecklistValue = ChecklistEntry[];

const DEFAULT_RESULT_COLORS = ['#22A052', '#D93025', '#8F8F8F', '#5A6FA8'] as const;

const DEFAULT_RESULTS: ChecklistResultOption[] = [
  { id: 'pass', label: '通过', color: DEFAULT_RESULT_COLORS[0] },
  { id: 'fail', label: '不通过', color: DEFAULT_RESULT_COLORS[1] },
  { id: 'na', label: '不适用', color: DEFAULT_RESULT_COLORS[2] },
];

export function checklistItems(node: MobileSchemaNode): ChecklistItemDef[] {
  const items = node.props?.items;
  if (!Array.isArray(items)) return [];
  return items
    .map((item, index) => {
      if (typeof item !== 'object' || item == null) return null;
      const raw = item as Record<string, unknown>;
      const label = String(raw.label ?? raw.content ?? `检查项${index + 1}`);
      const id = typeof raw.id === 'string' && raw.id ? raw.id : `item-${index}`;
      return { id, label, required: raw.required === true };
    })
    .filter((item): item is ChecklistItemDef => Boolean(item));
}

export function checklistResults(node: MobileSchemaNode): ChecklistResultOption[] {
  const results = node.props?.results;
  if (!Array.isArray(results) || results.length === 0) {
    return DEFAULT_RESULTS;
  }
  const parsed = results
    .map((result, index) => {
      if (typeof result !== 'object' || result == null) return null;
      const raw = result as Record<string, unknown>;
      const label = String(raw.label ?? `结果${index + 1}`);
      const id = typeof raw.id === 'string' && raw.id ? raw.id : `result-${index}`;
      const color = typeof raw.color === 'string' && isHexColor(raw.color)
        ? raw.color
        : (DEFAULT_RESULT_COLORS[index] ?? DEFAULT_RESULT_COLORS[3]);
      return { id, label, color };
    })
    .filter((result): result is ChecklistResultOption => Boolean(result));
  return parsed.length >= 2 ? parsed : DEFAULT_RESULTS;
}

export function entryHasContent(entry: ChecklistEntry) {
  return (
    String(entry.description ?? '').trim() !== '' ||
    (Array.isArray(entry.images) && entry.images.length > 0)
  );
}

/**
 * 表单值 → 内部 entries。历史值可能是 `itemId/result/remark/photos` 这套旧键，
 * 两套键都要认，否则草稿与审批数据会丢状态。
 */
export function entriesFromValue(value: unknown, items: ChecklistItemDef[]): ChecklistValue {
  if (!Array.isArray(value)) {
    return items.map((item) => ({
      id: item.id,
      name: item.label,
      status: null,
      description: '',
      images: [],
    }));
  }
  const byId = new Map<string, ChecklistEntry>();
  for (const entry of value) {
    if (typeof entry !== 'object' || entry == null) continue;
    const raw = entry as Record<string, unknown>;
    const id = typeof raw.id === 'string' ? raw.id : typeof raw.itemId === 'string' ? raw.itemId : '';
    if (!id) continue;
    byId.set(id, {
      id,
      name: typeof raw.name === 'string' ? raw.name : '',
      status: typeof raw.status === 'string' ? raw.status : typeof raw.result === 'string' ? raw.result : null,
      description: typeof raw.description === 'string' ? raw.description : typeof raw.remark === 'string' ? raw.remark : '',
      images: Array.isArray(raw.images) ? raw.images : Array.isArray(raw.photos) ? raw.photos : [],
    });
  }
  return items.map((item) => byId.get(item.id) ?? {
    id: item.id,
    name: item.label,
    status: null,
    description: '',
    images: [],
  });
}

export function checklistSummary(node: MobileSchemaNode, value: unknown): string {
  const items = checklistItems(node);
  const entries = entriesFromValue(value, items);
  const results = checklistResults(node);
  const counts = new Map<string, number>();
  for (const entry of entries) {
    if (!entry.status) continue;
    counts.set(entry.status, (counts.get(entry.status) ?? 0) + 1);
  }
  if (counts.size === 0) {
    return '未完成';
  }
  const parts = results
    .filter((result) => counts.has(result.id))
    .map((result) => `${counts.get(result.id)} 项${result.label}`);
  return parts.length > 0 ? parts.join('、') : '未完成';
}
