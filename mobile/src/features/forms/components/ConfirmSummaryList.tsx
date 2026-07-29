import { getFieldDefinition } from '../schema/fieldRegistry';
import type { MobileFormValues, MobileSchemaNode } from '../schema/types';
import { isVisibleNode } from '../schema/validators';

export type SummaryRow = {
  id: string;
  label: string;
  value: string;
};

export type ConfirmSummaryListProps = {
  schema: MobileSchemaNode[];
  values: MobileFormValues;
  emptyText?: string;
};

export function ConfirmSummaryList({
  schema,
  values,
  emptyText = '暂无表单字段',
}: ConfirmSummaryListProps) {
  const rows = summarizeSchemaRows(schema, values);
  if (rows.length === 0) {
    return <p className="af-empty-text">{emptyText}</p>;
  }
  return (
    <dl className="af-summary-list">
      {rows.map((row) => (
        <div key={row.id} className="af-summary-row" data-testid={`summary-${row.id}`}>
          <dt>{row.label}</dt>
          <dd>{row.value || '未填写'}</dd>
        </div>
      ))}
    </dl>
  );
}

export function summarizeSchemaRows(schema: MobileSchemaNode[], values: MobileFormValues): SummaryRow[] {
  return schema.flatMap((node) => summarizeNode(node, values));
}

function summarizeNode(node: MobileSchemaNode, values: MobileFormValues): SummaryRow[] {
  if (!isVisibleNode(node, values) || node.type === 'description') return [];
  if (node.children && node.type !== 'table_list') {
    return node.children.flatMap((child) => summarizeNode(child, values));
  }
  return [{
    id: node.id,
    label: node.label ?? node.id,
    value: getFieldDefinition(node.type).summarize(node, values[node.id]),
  }];
}

export default ConfirmSummaryList;
