import { useMemo } from 'react';
import type { FieldMode, MobileFormValues, MobileSchemaNode } from '../schema/types';
import {
  otherFilesEntry,
  schemaHasFileId,
  summaryEntries,
  type SummaryEntry,
} from './confirmSummaryEntries';
import { renderSummaryDetail } from './ConfirmSummaryDetails';
import { DepartmentSummaryItem, SummaryItemFrame, UserSummaryItem } from './ConfirmSummaryViews';
import { mediaFiles } from './confirmSummaryFormat';
import type { MediaFile } from './MediaPreview';

export type { SummaryRow } from './confirmSummaryEntries';
export { summarizeSchemaRows } from './confirmSummaryEntries';

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

/** 明细表行展开时递归渲染同一列表；模块级定义保证回调身份稳定。 */
function renderNestedList(
  schema: MobileSchemaNode[],
  values: MobileFormValues,
  fieldModes: Record<string, FieldMode>,
) {
  return <ConfirmSummaryList schema={schema} values={values} fieldModes={fieldModes} nested />;
}

function SummaryItem({ entry }: { entry: SummaryEntry }) {
  if (entry.node.type === 'user_picker') {
    return <UserSummaryItem entry={entry} />;
  }
  if (entry.node.type === 'dept_picker') {
    return <DepartmentSummaryItem entry={entry} />;
  }

  const detail = renderSummaryDetail(
    entry.node,
    entry.rawValue,
    entry.extraFiles,
    entry.fieldModes,
    renderNestedList,
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

export default ConfirmSummaryList;
