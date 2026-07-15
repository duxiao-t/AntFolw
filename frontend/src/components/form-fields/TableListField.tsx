import { Button, Table } from 'antd';
import { FormRenderer } from '../FormRenderer/FormRenderer';
import type { FieldType } from '../../registry/types';

export const TableListField: FieldType = {
  type: 'table_list',
  label: '明细表',
  icon: 'table',
  defaultProps: { minRows: 1, maxRows: 50 },
  Component: ({ node, mode, value, onChange }) => {
    const rows: any[] = Array.isArray(value) ? value : [];
    const update = (idx: number, row: any) => {
      const next = rows.slice();
      next[idx] = row;
      onChange?.(next);
    };
    const remove = (idx: number) => {
      const next = rows.slice();
      next.splice(idx, 1);
      onChange?.(next);
    };
    const addRow = () => onChange?.([...rows, {}]);
    return (
      <fieldset
        data-field-id={node.id}
        style={{ border: '1px dashed #bbb', padding: 12, margin: '8px 0' }}
      >
        <legend>{node.label ?? '明细表'}</legend>
        <Table
          dataSource={rows.map((r, i) => ({ ...r, _idx: i }))}
          rowKey="_idx"
          pagination={false}
          columns={(node.children ?? []).flatMap((c) => [
            {
              title: c.label ?? c.type,
              key: c.id,
              render: (_: any, r: any) => (
                <FormRenderer
                  schema={[c]}
                  value={r[c.id]}
                  onChange={(v: any) => update(r._idx, { ...r, [c.id]: v })}
                  mode={mode}
                />
              ),
            },
          ])}
        />
        {mode === 'runtime-fill' && (
          <div style={{ marginTop: 8, display: 'flex', gap: 8 }}>
            <Button onClick={addRow}>+ 新增一行</Button>
            <Button danger onClick={() => rows.length && remove(rows.length - 1)}>
              删除末行
            </Button>
          </div>
        )}
      </fieldset>
    );
  },
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <label>明细表标题</label>
      <input value={node.label ?? ''} onChange={(e) => onChange({ ...node, label: e.target.value })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
    </div>
  ),
};
