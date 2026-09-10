import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { usePlatformAdapter } from '../../../shared/platform/PlatformProvider';
import {
  checklistItems,
  checklistResults,
  checklistSummary,
  entriesFromValue,
} from '../fields/ChecklistField';
import { allFieldOptions } from '../fields/fieldShared';
import { fetchMobileDepartment, fetchMobileUser, type MobilePickerDept, type MobilePickerUser } from '../files.api';
import {
  composeMatrixAxis,
  matrixCell,
  normalizeMatrixProps,
  normalizeMatrixValue,
} from '../schema/matrixFill';
import { getFieldDefinition } from '../schema/fieldRegistry';
import type { FieldMode, MobileFormValues, MobileSchemaNode } from '../schema/types';
import { fieldLabel, visibleNodeIds } from '../schema/validators';
import { ReadonlyMediaList, type MediaFile } from './MediaPreview';

const COMPACT_TEXT_LENGTH = 32;
const COMPACT_LIST_LIMIT = 3;

export type SummaryRow = {
  id: string;
  label: string;
  value: string;
};

type SummaryEntry = SummaryRow & {
  node: MobileSchemaNode;
  rawValue: unknown;
  fieldModes: Record<string, FieldMode>;
  extraFiles?: MediaFile[];
};

export type ConfirmSummaryListProps = {
  schema: MobileSchemaNode[];
  values: MobileFormValues;
  emptyText?: string;
  fieldModes?: Record<string, FieldMode>;
  includeEditable?: boolean;
  extraFiles?: MediaFile[];
  nested?: boolean;
};

/**
 * 已填内容的统一只读摘要：默认保持确认页的“左字段、右结果”行式结构，
 * 只有需要完整核对的内容才在当前行下方展开。
 */
export function ConfirmSummaryList({
  schema,
  values,
  emptyText = '暂无表单字段',
  fieldModes = {},
  includeEditable = true,
  extraFiles = [],
  nested = false,
}: ConfirmSummaryListProps) {
  const entries = useMemo(
    () => summaryEntries(schema, values, fieldModes, includeEditable),
    [fieldModes, includeEditable, schema, values],
  );
  const unlinkedFiles = useMemo(
    () => mediaFiles(extraFiles).filter((file) => !file.id || !schemaHasFileId(schema, values, file.id)),
    [extraFiles, schema, values],
  );
  const allEntries = unlinkedFiles.length > 0
    ? [...entries, otherFilesEntry(unlinkedFiles)]
    : entries;

  if (allEntries.length === 0) {
    return <p className="af-empty-text">{emptyText}</p>;
  }
  return (
    <div className={`confirm-summary-list${nested ? ' confirm-summary-list--nested' : ''}`}>
      {allEntries.map((entry) => <SummaryItem key={entry.id} entry={entry} />)}
    </div>
  );
}

export function summarizeSchemaRows(
  schema: MobileSchemaNode[],
  values: MobileFormValues,
  fieldModes: Record<string, FieldMode> = {},
  includeEditable = true,
): SummaryRow[] {
  return summaryEntries(schema, values, fieldModes, includeEditable)
    .map(({ id, label, value }) => ({ id, label, value }));
}

function summaryEntries(
  schema: MobileSchemaNode[],
  values: MobileFormValues,
  fieldModes: Record<string, FieldMode>,
  includeEditable: boolean,
): SummaryEntry[] {
  const visibleIds = visibleNodeIds(schema, values);
  return collectSummaryEntries(schema, values, visibleIds, fieldModes, includeEditable);
}

function collectSummaryEntries(
  nodes: MobileSchemaNode[],
  values: MobileFormValues,
  visibleIds: ReadonlySet<string>,
  fieldModes: Record<string, FieldMode>,
  includeEditable: boolean,
): SummaryEntry[] {
  return nodes.flatMap((node) => {
    const mode = fieldModes[node.id] ?? 'readonly';
    if (!visibleIds.has(node.id) || mode === 'hidden' || node.type === 'description') {
      return [];
    }
    if (node.type !== 'table_list' && node.children?.length) {
      return collectSummaryEntries(node.children, values, visibleIds, fieldModes, includeEditable);
    }
    if (!includeEditable && mode === 'fill') {
      return [];
    }
    const rawValue = values[node.id];
    return [{
      id: node.id,
      label: fieldLabel(node),
      value: summaryText(node, rawValue),
      node,
      rawValue,
      fieldModes,
    }];
  });
}

function SummaryItem({ entry }: { entry: SummaryEntry }) {
  if (entry.node.type === 'user_picker') {
    return <UserSummaryItem entry={entry} />;
  }
  if (entry.node.type === 'dept_picker') {
    return <DepartmentSummaryItem entry={entry} />;
  }

  const detail = detailFor(
    entry.node,
    entry.rawValue,
    entry.extraFiles,
    entry.fieldModes,
  );
  return (
    <SummaryItemFrame
      id={entry.id}
      label={entry.label}
      value={entry.value}
      detail={detail}
    />
  );
}

function UserSummaryItem({ entry }: { entry: SummaryEntry }) {
  const endpoint = String(entry.node.props?.searchEndpoint ?? '/api/mobile/users');
  const multiple = entry.node.props?.multiple === true;
  const ids = useMemo(() => pickerIds(entry.rawValue, multiple), [entry.rawValue, multiple]);
  const users = usePickerUsers(endpoint, ids);
  const names = users.map((user) => user.displayName || `用户#${user.id}`);
  const detail = ids.length > 0 ? (
    <div className="confirm-summary-people confirm-summary-people--end">
      {users.map((user) => (
        <div key={user.id} className="confirm-summary-person">
          <strong>{user.displayName || `用户#${user.id}`}</strong>
          <small>{identityMeta(user, user.id)}</small>
        </div>
      ))}
    </div>
  ) : null;
  return (
    <SummaryItemFrame
      id={entry.id}
      label={entry.label}
      value={compactLabels(names)}
      detail={detail}
    />
  );
}

function DepartmentSummaryItem({ entry }: { entry: SummaryEntry }) {
  const endpoint = String(entry.node.props?.searchEndpoint ?? '/api/mobile/departments');
  const multiple = entry.node.props?.multiple === true;
  const ids = useMemo(() => pickerIds(entry.rawValue, multiple), [entry.rawValue, multiple]);
  const departments = usePickerDepartments(endpoint, ids);
  const names = departments.map((department) => department.name || `部门 #${department.id}`);
  const detail = ids.length === 0 ? null : (
    <div className="confirm-summary-definition-list">
      {departments.flatMap((department) => [
        <div key={`${department.id}-name`}><span>部门名称</span><strong>{department.name || `部门 #${department.id}`}</strong></div>,
        <div key={`${department.id}-id`}><span>部门编号</span><strong>{department.id}</strong></div>,
      ])}
    </div>
  );
  return <SummaryItemFrame id={entry.id} label={entry.label} value={compactLabels(names)} detail={detail} />;
}

function SummaryItemFrame({
  id,
  label,
  value,
  detail,
}: {
  id: string;
  label: string;
  value: ReactNode;
  detail: ReactNode;
}) {
  const [expanded, setExpanded] = useState(false);
  const expandable = detail != null;
  const detailId = `summary-detail-${id}`;
  const content = (
    <>
      <span className="confirm-row__k">{label}</span>
      <span className="confirm-row__value-wrap">
        <span className="confirm-row__v">{value}</span>
        {expandable ? <span className={`confirm-row__chevron${expanded ? ' is-open' : ''}`} aria-hidden="true">⌄</span> : null}
      </span>
    </>
  );
  return (
    <div className={`confirm-summary-item${expanded ? ' is-expanded' : ''}`} data-testid={`summary-${id}`}>
      {expandable ? (
        <button
          type="button"
          className="confirm-row confirm-row--expandable"
          aria-expanded={expanded}
          aria-controls={detailId}
          onClick={() => setExpanded((current) => !current)}
        >
          {content}
        </button>
      ) : <div className="confirm-row">{content}</div>}
      {expanded ? <div id={detailId} className="confirm-row__detail">{detail}</div> : null}
    </div>
  );
}

function detailFor(
  node: MobileSchemaNode,
  value: unknown,
  extraFiles?: MediaFile[],
  fieldModes: Record<string, FieldMode> = {},
): ReactNode {
  if (extraFiles) {
    return <ReadonlyMediaList files={extraFiles} />;
  }
  if (isMediaField(node.type)) {
    const files = mediaFiles(value);
    return files.length > 0 ? <ReadonlyMediaList files={files} /> : null;
  }
  if (node.type === 'checklist') {
    const entries = entriesFromValue(value, checklistItems(node));
    return entries.some((entry) => entry.status || entry.description.trim() || entry.images.length > 0)
      ? <ChecklistSummaryDetail node={node} value={value} />
      : null;
  }
  if (node.type === 'table_list') {
    return tableRows(value).length > 0
      ? <TableSummaryDetail node={node} value={value} fieldModes={fieldModes} /> : null;
  }
  if (node.type === 'matrix_fill') {
    return matrixHasContent(node, value) ? <MatrixSummaryDetail node={node} value={value} /> : null;
  }
  if (node.type === 'location') {
    const location = locationValue(value);
    return location ? <LocationSummaryDetail location={location} /> : null;
  }
  if (isOptionField(node.type)) {
    const labels = optionLabels(node, value);
    return optionNeedsDetail(node, value, labels)
      ? <SummaryTags labels={labels} endAligned={node.type === 'multi_select'} />
      : null;
  }
  if (isTextField(node.type)) {
    const text = fullText(value);
    return textNeedsDetail(text) ? <p className="confirm-row__detail-text">{text}</p> : null;
  }
  if (node.type === 'date_range' && Array.isArray(value)) {
    return null;
  }
  if (typeof value === 'object' && value != null) {
    return <pre className="confirm-row__detail-json">{safeJson(value)}</pre>;
  }
  return null;
}

function ChecklistSummaryDetail({ node, value }: { node: MobileSchemaNode; value: unknown }) {
  const items = checklistItems(node);
  const results = checklistResults(node);
  const entries = entriesFromValue(value, items);
  return (
    <div className="confirm-checklist-detail">
      {entries.map((entry) => {
        const result = results.find((option) => option.id === entry.status);
        const files = mediaFiles(entry.images);
        return (
          <section key={entry.id} className="confirm-checklist-detail__item">
            <div className="confirm-checklist-detail__head">
              <strong>{entry.name || items.find((item) => item.id === entry.id)?.label || entry.id}</strong>
              <span style={{ color: result?.color }}>{result?.label ?? entry.status ?? '未填写'}</span>
            </div>
            {entry.description.trim() ? <p>{entry.description}</p> : null}
            {files.length > 0 ? <ReadonlyMediaList files={files} /> : null}
          </section>
        );
      })}
    </div>
  );
}

function TableSummaryDetail({
  node,
  value,
  fieldModes,
}: {
  node: MobileSchemaNode;
  value: unknown;
  fieldModes: Record<string, FieldMode>;
}) {
  const rows = tableRows(value);
  const children = (node.children ?? []).filter((child) => fieldModes[child.id] !== 'hidden');
  const rowIds = useMemo(() => tableRowIds(rows), [rows]);
  const [expandedRows, setExpandedRows] = useState<ReadonlySet<string>>(new Set());
  return (
    <div className="confirm-table-detail">
      {rows.map((row, index) => {
        const rowId = rowIds[index] ?? safeJson(row);
        const rowValues = isRecord(row) ? row : {};
        const rowSummary = summarizeSchemaRows(children, rowValues, fieldModes)
          .slice(0, COMPACT_LIST_LIMIT)
          .map((item) => `${item.label}: ${item.value}`)
          .join(' · ') || '未填写';
        const expanded = expandedRows.has(rowId);
        return (
          <section key={rowId} className="confirm-table-detail__row">
            <button
              type="button"
              aria-expanded={expanded}
              onClick={() => setExpandedRows((current) => {
                const next = new Set(current);
                if (next.has(rowId)) next.delete(rowId); else next.add(rowId);
                return next;
              })}
            >
              <strong>第 {index + 1} 行</strong>
              <span>{compactText(rowSummary)}</span>
              <i aria-hidden="true">{expanded ? '⌃' : '⌄'}</i>
            </button>
            {expanded ? <ConfirmSummaryList schema={children} values={rowValues} fieldModes={fieldModes} nested /> : null}
          </section>
        );
      })}
    </div>
  );
}

function SummaryTags({ labels, endAligned = false }: { labels: string[]; endAligned?: boolean }) {
  const occurrences = new Map<string, number>();
  return (
    <div className={`confirm-summary-tags${endAligned ? ' confirm-summary-tags--end' : ''}`}>
      {labels.map((label) => {
        const occurrence = (occurrences.get(label) ?? 0) + 1;
        occurrences.set(label, occurrence);
        return <span key={`${label}-${occurrence}`}>{label}</span>;
      })}
    </div>
  );
}

function MatrixSummaryDetail({ node, value }: { node: MobileSchemaNode; value: unknown }) {
  const props = normalizeMatrixProps(node.props);
  const matrix = normalizeMatrixValue(value, node.props);
  const rows = composeMatrixAxis(props, matrix, 'row');
  const columns = composeMatrixAxis(props, matrix, 'column');
  return (
    <div className="confirm-matrix-detail">
      <div className="af-matrix__scroll">
        <table className="af-matrix__table">
          <caption className="af-visually-hidden">{fieldLabel(node)}</caption>
          <thead>
            <tr>
              <th className="af-matrix__corner" scope="col">行 / 列</th>
              {columns.map((column) => <th key={column.id} scope="col"><span>{column.label}</span></th>)}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.id}>
                <th className="af-matrix__row-head" scope="row"><span>{row.label}</span></th>
                {columns.map((column) => {
                  const cell = matrixCell(matrix, row.id, column.id, props.cellType);
                  return <td key={`${row.id}:${column.id}`}><span className="af-matrix__readonly">{cell == null || cell === '' ? '未填写' : String(cell)}</span></td>;
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function LocationSummaryDetail({ location }: { location: LocationValue }) {
  const platform = usePlatformAdapter();
  return (
    <div className="confirm-summary-definition-list">
      {location.name || location.address ? <div><span>位置</span><strong>{location.name || location.address}</strong></div> : null}
      <div><span>坐标</span><strong>{location.latitude.toFixed(6)}, {location.longitude.toFixed(6)}</strong></div>
      {location.coordinateSystem ? <div><span>坐标系</span><strong>{location.coordinateSystem}</strong></div> : null}
      {location.accuracy != null ? <div><span>精度</span><strong>约 {Math.round(location.accuracy)} 米</strong></div> : null}
      {platform.openLocation ? <button type="button" className="af-link-button" onClick={() => void platform.openLocation?.(location)}>在地图中打开</button> : null}
    </div>
  );
}

function summaryText(node: MobileSchemaNode, value: unknown): string {
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

function optionLabels(node: MobileSchemaNode, value: unknown): string[] {
  const multiple = node.type === 'multi_select' || node.type === 'checkbox';
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

function optionNeedsDetail(node: MobileSchemaNode, value: unknown, labels: string[]) {
  const multiple = node.type === 'multi_select' || node.type === 'checkbox';
  return labels.length > COMPACT_LIST_LIMIT
    || labels.some((label) => label.length > COMPACT_TEXT_LENGTH || label.startsWith('其他：'))
    || (multiple && labels.length > 1)
    || (typeof value === 'object' && value != null && !Array.isArray(value));
}

function isOptionField(type: string) {
  return ['select', 'radio', 'multi_select', 'checkbox'].includes(type);
}

function isTextField(type: string) {
  return ['text', 'textarea', 'search', 'scan_code'].includes(type);
}

function isMediaField(type: string) {
  return ['file_upload', 'image_upload', 'video_upload', 'audio_upload'].includes(type);
}

function mediaCountLabel(type: string, count: number) {
  switch (type) {
    case 'image_upload': return `${count}张图片`;
    case 'video_upload': return `${count}个视频`;
    case 'audio_upload': return `${count}段录音`;
    default: return `${count}个附件`;
  }
}

function otherFilesEntry(files: MediaFile[]): SummaryEntry {
  return {
    id: '__other_files__',
    label: '其他附件',
    value: `${files.length}个附件`,
    node: { id: '__other_files__', type: 'file_upload', label: '其他附件' },
    rawValue: files,
    fieldModes: {},
    extraFiles: files,
  };
}

function mediaFiles(value: unknown): MediaFile[] {
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

function schemaHasFileId(schema: MobileSchemaNode[], values: MobileFormValues, targetId: string) {
  const visibleIds = visibleNodeIds(schema, values);
  return schema.some((node) => hasVisibleFileId(node, values, visibleIds, targetId));
}

function hasVisibleFileId(
  node: MobileSchemaNode,
  values: MobileFormValues,
  visibleIds: ReadonlySet<string>,
  targetId: string,
): boolean {
  if (!visibleIds.has(node.id)) return false;
  if (node.type === 'table_list') {
    return tableRows(values[node.id]).some((row) => isRecord(row)
      && (node.children ?? []).some((child) => hasVisibleFileId(child, row, visibleNodeIds(node.children ?? [], row), targetId)));
  }
  if (node.children?.length) {
    return node.children.some((child) => hasVisibleFileId(child, values, visibleIds, targetId));
  }
  if (node.type === 'checklist') {
    return entriesFromValue(values[node.id], checklistItems(node))
      .some((entry) => mediaFiles(entry.images).some((file) => file.id === targetId));
  }
  return isMediaField(node.type) && mediaFiles(values[node.id]).some((file) => file.id === targetId);
}

function tableRows(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function tableRowIds(rows: unknown[]) {
  const occurrences = new Map<string, number>();
  return rows.map((row) => {
    const base = isRecord(row) && typeof row.id === 'string' && row.id
      ? `id:${row.id}` : safeJson(row);
    const occurrence = (occurrences.get(base) ?? 0) + 1;
    occurrences.set(base, occurrence);
    return `${base}-${occurrence}`;
  });
}

function matrixHasContent(node: MobileSchemaNode, value: unknown) {
  const matrix = normalizeMatrixValue(value, node.props);
  if (matrix.customRows.length > 0 || matrix.customColumns.length > 0) return true;
  return Object.values(matrix.cells).some((row) => Object.values(row).some((cell) =>
    typeof cell === 'number' || (typeof cell === 'string' && cell.trim() !== ''),
  ));
}

type LocationValue = {
  latitude: number;
  longitude: number;
  name?: string;
  address?: string;
  coordinateSystem?: 'WGS84' | 'GCJ02';
  accuracy?: number;
};

function locationValue(value: unknown): LocationValue | null {
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

function locationLabel(location: LocationValue) {
  return location.name || location.address || `${location.latitude.toFixed(6)}, ${location.longitude.toFixed(6)}`;
}

function pickerIds(value: unknown, multiple: boolean) {
  const source = multiple ? (Array.isArray(value) ? value : [value]) : [value];
  return [...new Set(source.flatMap((item) => {
    if (typeof item === 'number' && Number.isSafeInteger(item)) return [item];
    return isRecord(item) && typeof item.id === 'number' && Number.isSafeInteger(item.id) ? [item.id] : [];
  }))].slice(0, multiple ? undefined : 1);
}

function usePickerUsers(endpoint: string, ids: number[]) {
  const [users, setUsers] = useState<Record<number, MobilePickerUser>>({});
  useEffect(() => {
    if (ids.length === 0) return undefined;
    let active = true;
    void Promise.all(ids.map((id) => fetchMobileUser(endpoint, id).catch(() => fallbackUser(id))))
      .then((items) => {
        if (!active) return;
        setUsers((current) => ({ ...current, ...Object.fromEntries(items.map((item) => [item.id, item])) }));
      });
    return () => { active = false; };
  }, [endpoint, ids]);
  return ids.map((id) => users[id] ?? fallbackUser(id));
}

function usePickerDepartments(endpoint: string, ids: number[]) {
  const [departments, setDepartments] = useState<Record<number, MobilePickerDept>>({});
  useEffect(() => {
    if (ids.length === 0) return undefined;
    let active = true;
    void Promise.all(ids.map((id) => fetchMobileDepartment(endpoint, id)
      .catch(() => ({ id, name: `部门 #${id}` }))))
      .then((items) => {
        if (!active) return;
        setDepartments((current) => ({
          ...current,
          ...Object.fromEntries(items.map((item) => [item.id, item])),
        }));
      });
    return () => { active = false; };
  }, [endpoint, ids]);
  return ids.map((id) => departments[id] ?? { id, name: `部门 #${id}` });
}

function fallbackUser(id: number): MobilePickerUser {
  return { id, displayName: `用户#${id}` };
}

function identityMeta(user: MobilePickerUser, id: number) {
  const department = user.department || '未设置部门';
  const employeeNo = user.employeeNo || user.username || String(id);
  return `${department} · 工号 ${employeeNo}`;
}

function primitiveValues(value: unknown): Array<string | number> {
  const source = Array.isArray(value) ? value : [value];
  return source.filter((item): item is string | number => typeof item === 'string' || typeof item === 'number');
}

function compactLabels(values: string[]) {
  if (values.length === 0) return '未填写';
  const visible = values.slice(0, COMPACT_LIST_LIMIT).map(compactText).join('、');
  return values.length > COMPACT_LIST_LIMIT ? `${visible} 等${values.length}项` : visible;
}

function fullText(value: unknown) {
  if (value == null || value === '') return '未填写';
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return String(value);
  return safeJson(value);
}

function compactText(value: string) {
  const normalized = value.replace(/\s+/g, ' ').trim();
  if (!normalized) return '未填写';
  const chars = Array.from(normalized);
  return chars.length > COMPACT_TEXT_LENGTH
    ? `${chars.slice(0, COMPACT_TEXT_LENGTH).join('')}…`
    : normalized;
}

function textNeedsDetail(value: string) {
  return value !== '未填写' && (value.includes('\n') || Array.from(value).length > COMPACT_TEXT_LENGTH);
}

function safeJson(value: unknown) {
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return '已填写';
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value);
}

function stringValue(value: unknown) {
  return typeof value === 'string' ? value : '';
}

function finiteNumber(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

export default ConfirmSummaryList;
