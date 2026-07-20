import { useEffect, useState } from 'react';
import { getFieldDefinition } from '../schema/fieldRegistry';
import type { MobileFormValues, MobileFieldProps, MobileSchemaNode } from '../schema/types';
import { fieldLabel, FieldShell } from './fieldShared';

type RowValue = Record<string, unknown>;
type RowItem = {
  key: string;
  value: RowValue;
};

export function TableListField(props: MobileFieldProps) {
  const label = fieldLabel(props.node);
  const minRows = numberValue(props.node.props?.minRows, 0);
  const maxRows = numberValue(props.node.props?.maxRows, Number.POSITIVE_INFINITY);
  const [rows, setRows] = useState<RowItem[]>(() => normalizeRows(props.value, minRows));
  const [expanded, setExpanded] = useState<string[]>([]);

  useEffect(() => {
    setRows((current) => reconcileRows(current, props.value, minRows));
  }, [minRows, props.value]);

  const canAdd = rows.length < maxRows;

  return (
    <FieldShell label={label} error={props.error}>
      <div style={{ display: 'grid', gap: 8 }}>
        {rows.map((row, index) => {
          const children = props.node.children ?? [];
          const rowMode = props.mode;
          return (
            <section key={row.key} style={{ border: '1px solid var(--af-color-border)', padding: 12, borderRadius: 6 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
                <strong>{`第${index + 1}行`}</strong>
                <div style={{ display: 'flex', gap: 8 }}>
                  <button type="button" onClick={() => toggle(row.key)}>
                    {expanded.includes(row.key) ? `收起 第${index + 1}行` : `展开 第${index + 1}行`}
                  </button>
                  <button type="button" onClick={() => expand(row.key)}>
                    编辑 第{index + 1}行
                  </button>
                  <button
                    type="button"
                    aria-label={`删除 第${index + 1}行`}
                    disabled={rows.length <= minRows}
                    onClick={() => deleteRow(row.key)}
                  >
                    删除
                  </button>
                </div>
              </div>
              {expanded.includes(row.key) ? (
                <div style={{ display: 'grid', gap: 8, marginTop: 8 }}>
                  {children.map((child) => renderChild(child, row.value, index, rowMode))}
                </div>
              ) : null}
            </section>
          );
        })}
        <button type="button" disabled={!canAdd} onClick={addRow}>
          新增{label}
        </button>
      </div>
    </FieldShell>
  );

  function addRow() {
    if (!canAdd) {
      return;
    }
    const next = [...rows, { key: createRowKey(), value: emptyRow(props.node.children ?? []) }];
    setRows(next);
    emit(next);
  }

  function deleteRow(rowKey: string) {
    if (rows.length <= minRows) {
      return;
    }
    const next = rows.filter((row) => row.key !== rowKey);
    setRows(next);
    emit(next);
    setExpanded((current) => current.filter((item) => item !== rowKey));
  }

  function toggle(rowKey: string) {
    setExpanded((current) =>
      current.includes(rowKey) ? current.filter((item) => item !== rowKey) : [...current, rowKey],
    );
  }

  function expand(rowKey: string) {
    setExpanded((current) => current.includes(rowKey) ? current : [...current, rowKey]);
  }

  function emit(nextRows: RowItem[]) {
    props.onValueChange(props.node.id, nextRows.map((row) => row.value));
  }

  function renderChild(child: MobileSchemaNode, row: RowValue, rowIndex: number, mode: MobileFieldProps['mode']) {
    const definition = getFieldDefinition(child.type);
    const value = row[child.id];
    const values: MobileFormValues = row;
    const updateRow = (fieldId: string, nextValue: unknown) => {
      const nextRows = rows.map((current, currentIndex) =>
        currentIndex === rowIndex
          ? { ...current, value: { ...current.value, [fieldId]: nextValue } }
          : current,
      );
      setRows(nextRows);
      emit(nextRows);
    };
    return (
      <definition.Component
        key={child.id}
        node={child}
        value={value}
        values={values}
        mode={mode}
        onValueChange={updateRow}
        renderChildren={(nestedChildren) =>
          nestedChildren.map((nestedChild) => renderChild(nestedChild, row, rowIndex, mode))
        }
      />
    );
  }
}

function normalizeRows(value: unknown, minRows: number): RowItem[] {
  return reconcileRows([], value, minRows);
}

function reconcileRows(current: RowItem[], value: unknown, minRows: number): RowItem[] {
  const nextValues = normalizeRowValues(value, minRows);
  const rows = nextValues.map((rowValue, index) => ({
    key: current[index]?.key ?? createRowKey(),
    value: rowValue,
  }));
  return rows;
}

function normalizeRowValues(value: unknown, minRows: number): RowValue[] {
  if (!Array.isArray(value)) {
    return Array.from({ length: minRows }, () => ({}));
  }
  const rows = value.filter((item): item is RowValue => typeof item === 'object' && item != null);
  while (rows.length < minRows) {
    rows.push({});
  }
  return rows;
}

function emptyRow(children: MobileSchemaNode[]) {
  const row: RowValue = {};
  for (const child of children) {
    row[child.id] = defaultValue(child);
  }
  return row;
}

function defaultValue(node: MobileSchemaNode) {
  if (node.type === 'multi_select') {
    return [];
  }
  if (node.type === 'date_range') {
    return ['', ''];
  }
  if (node.type === 'file_upload') {
    return [];
  }
  return '';
}

function numberValue(value: unknown, fallback: number) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function createRowKey() {
  return `row-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
