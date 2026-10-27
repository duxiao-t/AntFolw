import { checklistSummary } from '../fields/ChecklistField';
import { allFieldOptions } from '../fields/fieldShared';
import { composeMatrixAxis, normalizeMatrixProps, normalizeMatrixValue } from '../schema/matrixFill';
import { getFieldDefinition } from '../schema/fieldRegistry';
import type { MobileSchemaNode } from '../schema/types';
import type { MediaFile } from './MediaPreview';

export const COMPACT_TEXT_LENGTH = 32;
export const COMPACT_LIST_LIMIT = 3;

/** 单字段的"右侧摘要"文案：口径集中在此，视图层不再二次判断空值。 */
export function summaryText(node: MobileSchemaNode, value: unknown): string {
  if (node.type === 'user_picker') {
    const ids = pickerIds(value, node.props?.multiple === true);
    return ids.length > 0 ? compactLabels(ids.map((id) => `用户#${id}`)) : '未填写';
  }
  if (node.type === 'dept_picker') {
    const ids = pickerIds(value, node.props?.multiple === true);
    return ids.length > 0 ? compactLabels(ids.map((id) => `部门 #${id}`)) : '未填写';
  }
  if (node.type === 'checklist') {
    const summary = checklistSummary(node, value);
    return summary === '未完成' ? '未填写' : summary.replace(/(\d+) 项/g, '$1项');
  }
  if (node.type === 'table_list') {
    const count = tableRows(value).length;
    return count > 0 ? `${count}条明细` : '未填写';
  }
  if (node.type === 'matrix_fill') {
    if (!matrixHasContent(node, value)) return '未填写';
    const props = normalizeMatrixProps(node.props);
    const matrix = normalizeMatrixValue(value, node.props);
    return `${composeMatrixAxis(props, matrix, 'row').length} 行 × ${composeMatrixAxis(props, matrix, 'column').length} 列`;
  }
  if (node.type === 'location') {
    const location = locationValue(value);
    return location ? locationLabel(location) : '未填写';
  }
  if (isMediaField(node.type)) {
    const count = mediaFiles(value).length;
    if (count === 0) return '未填写';
    return mediaCountLabel(node.type, count);
  }
  if (isOptionField(node.type)) {
    return compactLabels(optionLabels(node, value));
  }
  if (isTextField(node.type)) {
    return compactText(fullText(value));
  }
  return compactText(safeFieldSummary(node, value));
}

/** 兜底走字段注册表；不支持的字段类型或抛错时降级为「已填写」，避免历史脏值打断整页。 */
function safeFieldSummary(node: MobileSchemaNode, value: unknown) {
  try {
    const definition = getFieldDefinition(node.type);
    if (definition.type === 'unsupported') {
      if (value == null || value === '') return '未填写';
      return typeof value === 'object' ? '已填写' : String(value);
    }
    return definition.summarize(node, value) || '未填写';
  } catch {
    if (value == null || value === '') return '未填写';
    return typeof value === 'object' ? '已填写' : String(value);
  }
}

export function optionLabels(node: MobileSchemaNode, value: unknown): string[] {
  const multiple = node.type === 'multi_select';
  const values = multiple ? primitiveValues(value) : primitiveValues([value]);
  if (values.length === 0) return [];
  const options = allFieldOptions(node);
  const standardOptions = options.filter((option) => !option.isOther);
  const otherOption = options.find((option) => option.isOther);
  return values.map((item) => {
    const option = standardOptions.find((candidate) => candidate.value === item);
    if (option) return option.label;
    if (otherOption) return item === '__antflow_other__' ? '其他' : `其他：${String(item)}`;
    return String(item);
  });
}

export function optionNeedsDetail(node: MobileSchemaNode, value: unknown, labels: string[]) {
  const multiple = node.type === 'multi_select';
  return labels.length > COMPACT_LIST_LIMIT
    || labels.some((label) => label.length > COMPACT_TEXT_LENGTH || label.startsWith('其他：'))
    || (multiple && labels.length > 1)
    || (typeof value === 'object' && value != null && !Array.isArray(value));
}

export function isOptionField(type: string) {
  return ['select', 'multi_select'].includes(type);
}

export function isTextField(type: string) {
  return ['text', 'textarea', 'scan_code'].includes(type);
}

export function isMediaField(type: string) {
  return ['file_upload', 'image_upload', 'video_upload', 'audio_upload'].includes(type);
}

export function mediaCountLabel(type: string, count: number) {
  switch (type) {
    case 'image_upload': return `${count}张图片`;
    case 'video_upload': return `${count}个视频`;
    case 'audio_upload': return `${count}段录音`;
    default: return `${count}个附件`;
  }
}

export function mediaFiles(value: unknown): MediaFile[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    const file = mediaFile(item);
    return file ? [file] : [];
  });
}

function mediaFile(value: unknown): MediaFile | null {
  if (!isRecord(value)) return null;
  const id = stringValue(value.id);
  const name = stringValue(value.name) || stringValue(value.fileName);
  const contentUrl = stringValue(value.contentUrl);
  const url = stringValue(value.url);
  const contentType = stringValue(value.contentType);
  const size = finiteNumber(value.size) ?? finiteNumber(value.sizeBytes);
  const durationSeconds = finiteNumber(value.durationSeconds);
  if (!contentUrl && !url && !contentType && size == null && durationSeconds == null) return null;
  return {
    id: id || undefined,
    name: name || undefined,
    contentUrl: contentUrl || url,
    url: url || undefined,
    contentType: contentType || undefined,
    size,
    durationSeconds,
  };
}

export function tableRows(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

/** 明细表行 key：优先业务 id，重复时追加出现序号，保证 React 复用稳定。 */
export function tableRowIds(rows: unknown[]) {
  const occurrences = new Map<string, number>();
  return rows.map((row) => {
    const base = isRecord(row) && typeof row.id === 'string' && row.id
      ? `id:${row.id}` : safeJson(row);
    const occurrence = (occurrences.get(base) ?? 0) + 1;
    occurrences.set(base, occurrence);
    return `${base}-${occurrence}`;
  });
}

export function matrixHasContent(node: MobileSchemaNode, value: unknown) {
  const matrix = normalizeMatrixValue(value, node.props);
  if (matrix.customRows.length > 0 || matrix.customColumns.length > 0) return true;
  return Object.values(matrix.cells).some((row) => Object.values(row).some((cell) =>
    typeof cell === 'number' || (typeof cell === 'string' && cell.trim() !== ''),
  ));
}

export type LocationValue = {
  latitude: number;
  longitude: number;
  name?: string;
  address?: string;
  coordinateSystem?: 'WGS84' | 'GCJ02';
  accuracy?: number;
};

export function locationValue(value: unknown): LocationValue | null {
  if (!isRecord(value) || !Number.isFinite(value.latitude) || !Number.isFinite(value.longitude)) {
    return null;
  }
  const coordinateSystem = stringValue(value.coordinateSystem);
  return {
    latitude: value.latitude as number,
    longitude: value.longitude as number,
    name: stringValue(value.name) || undefined,
    address: stringValue(value.address) || undefined,
    coordinateSystem: coordinateSystem === 'WGS84' || coordinateSystem === 'GCJ02'
      ? coordinateSystem : undefined,
    accuracy: finiteNumber(value.accuracy),
  };
}

export function locationLabel(location: LocationValue) {
  return location.name || location.address || `${location.latitude.toFixed(6)}, ${location.longitude.toFixed(6)}`;
}

/** 选择器值既可能是 id、`{ id }`，也可能是数组；单选用 slice 截断保持"只认第一个"的旧口径。 */
export function pickerIds(value: unknown, multiple: boolean) {
  const source = multiple ? (Array.isArray(value) ? value : [value]) : [value];
  return [...new Set(source.flatMap((item) => {
    if (typeof item === 'number' && Number.isSafeInteger(item)) return [item];
    return isRecord(item) && typeof item.id === 'number' && Number.isSafeInteger(item.id) ? [item.id] : [];
  }))].slice(0, multiple ? undefined : 1);
}

export function primitiveValues(value: unknown): Array<string | number> {
  const source = Array.isArray(value) ? value : [value];
  return source.filter((item): item is string | number => typeof item === 'string' || typeof item === 'number');
}

export function compactLabels(values: string[]) {
  if (values.length === 0) return '未填写';
  const visible = values.slice(0, COMPACT_LIST_LIMIT).map(compactText).join('、');
  return values.length > COMPACT_LIST_LIMIT ? `${visible} 等${values.length}项` : visible;
}

export function fullText(value: unknown) {
  if (value == null || value === '') return '未填写';
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return String(value);
  return safeJson(value);
}

export function compactText(value: string) {
  const normalized = value.replace(/\s+/g, ' ').trim();
  if (!normalized) return '未填写';
  const chars = Array.from(normalized);
  return chars.length > COMPACT_TEXT_LENGTH
    ? `${chars.slice(0, COMPACT_TEXT_LENGTH).join('')}…`
    : normalized;
}

export function textNeedsDetail(value: string) {
  return value !== '未填写' && (value.includes('\n') || Array.from(value).length > COMPACT_TEXT_LENGTH);
}

export function safeJson(value: unknown) {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return '已填写';
  }
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value);
}

export function stringValue(value: unknown) {
  return typeof value === 'string' ? value : '';
}

export function finiteNumber(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}
