import { checklistItems, entriesFromValue } from '../fields/ChecklistField';
import type { FieldMode, MobileFormValues, MobileSchemaNode } from '../schema/types';
import { fieldLabel, visibleNodeIds } from '../schema/validators';
import { isMediaField, isRecord, mediaFiles, summaryText, tableRows } from './confirmSummaryFormat';
import type { MediaFile } from './MediaPreview';

export type SummaryRow = {
  id: string;
  label: string;
  value: string;
};

export type SummaryEntry = SummaryRow & {
  node: MobileSchemaNode;
  rawValue: unknown;
  fieldModes: Record<string, FieldMode>;
  extraFiles?: MediaFile[];
};

export function summarizeSchemaRows(
  schema: MobileSchemaNode[],
  values: MobileFormValues,
  fieldModes: Record<string, FieldMode> = {},
  includeEditable = true,
): SummaryRow[] {
  return summaryEntries(schema, values, fieldModes, includeEditable)
    .map(({ id, label, value }) => ({ id, label, value }));
}

export function summaryEntries(
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
    // 布局容器自身没有值，只在明细表以外的场景下钻到子节点。
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

/** 直提附件里那些没有挂到任何"可见字段"上的文件，统一收进「其他附件」一行。 */
export function otherFilesEntry(files: MediaFile[]): SummaryEntry {
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

export function schemaHasFileId(schema: MobileSchemaNode[], values: MobileFormValues, targetId: string) {
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
