import { useMemo, useState, type ReactNode } from 'react';
import { usePlatformAdapter } from '../../../shared/platform/PlatformProvider';
import { checklistItems, checklistResults, entriesFromValue } from '../fields/ChecklistField';
import {
  composeMatrixAxis,
  matrixCell,
  normalizeMatrixProps,
  normalizeMatrixValue,
} from '../schema/matrixFill';
import type { FieldMode, MobileFormValues, MobileSchemaNode } from '../schema/types';
import { fieldLabel } from '../schema/validators';
import { summarizeSchemaRows } from './confirmSummaryEntries';
import {
  COMPACT_LIST_LIMIT,
  compactText,
  fullText,
  isMediaField,
  isOptionField,
  isRecord,
  isTextField,
  locationValue,
  matrixHasContent,
  mediaFiles,
  optionLabels,
  optionNeedsDetail,
  safeJson,
  tableRowIds,
  tableRows,
  textNeedsDetail,
  type LocationValue,
} from './confirmSummaryFormat';
import { ReadonlyMediaList, type MediaFile } from './MediaPreview';

/** 明细表行展开后要复用外层列表，用回调注入以避开壳 ↔ 详情的循环依赖。 */
export type RenderNestedSummary = (
  schema: MobileSchemaNode[],
  values: MobileFormValues,
  fieldModes: Record<string, FieldMode>,
) => ReactNode;

/** 按字段类型决定"能否展开"以及展开后渲染什么；返回 null 表示该行不可展开。 */
export function renderSummaryDetail(
  node: MobileSchemaNode,
  value: unknown,
  extraFiles: MediaFile[] | undefined,
  fieldModes: Record<string, FieldMode>,
  renderNested: RenderNestedSummary,
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
      ? <TableSummaryDetail node={node} value={value} fieldModes={fieldModes} renderNested={renderNested} /> : null;
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
  renderNested,
}: {
  node: MobileSchemaNode;
  value: unknown;
  fieldModes: Record<string, FieldMode>;
  renderNested: RenderNestedSummary;
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
            {expanded ? renderNested(children, rowValues, fieldModes) : null}
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
