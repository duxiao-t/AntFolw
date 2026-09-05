import { Button, Card, Table } from 'antd';
import { FormRenderer } from '../FormRenderer/FormRenderer';
import type { FieldType } from '../../registry/types';

export const TableListField: FieldType = {
  type: 'table_list',
  label: '明细表',
  icon: 'table',
  defaultProps: {
    minRows: 1,
    maxRows: 50,
    defaultRows: 1,
    allowAdd: true,
    allowDelete: true,
    addButtonText: '新增一行',
    mobileMode: 'card',
  },
  Component: ({ node, mode, value, onChange, fieldModes }) => {
    const createDefaultRows = () =>
      Array.from({ length: node.props?.defaultRows ?? node.props?.minRows ?? 1 }).map(
        () => ({}),
      );
    const rows: any[] = Array.isArray(value) ? value : createDefaultRows();
    const children = node.children ?? [];
    const update = (idx: number, row: any) => {
      const next = rows.slice();
      next[idx] = row;
      onChange?.(next);
    };
    const remove = (idx: number) => {
      if (rows.length <= (node.props?.minRows ?? 1)) return;
      const next = rows.slice();
      next.splice(idx, 1);
      onChange?.(next);
    };
    const addRow = () => {
      if (rows.length >= (node.props?.maxRows ?? 50)) return;
      onChange?.([...rows, {}]);
    };
    const actionsVisible = mode === 'runtime-fill';
    const renderActions = (
      <div style={{ marginTop: 8, display: 'flex', gap: 8 }}>
        {node.props?.allowAdd !== false && (
          <Button disabled={rows.length >= (node.props?.maxRows ?? 50)} onClick={addRow}>
            {node.props?.addButtonText || '新增一行'}
          </Button>
        )}
        {node.props?.allowDelete !== false && (
          <Button
            danger
            disabled={rows.length <= (node.props?.minRows ?? 1)}
            onClick={() => rows.length && remove(rows.length - 1)}
          >
            删除末行
          </Button>
        )}
      </div>
    );
    return (
      <fieldset
        data-field-id={node.id}
        style={{ border: '1px dashed #bbb', borderRadius: 6, padding: 12, margin: '8px 0' }}
      >
        <legend>{node.label ?? '明细表'}</legend>
        {children.length === 0 ? (
          <div style={{ color: '#999' }}>请后续在明细表中配置子字段</div>
        ) : node.props?.mobileMode !== 'table' ? (
          <div style={{ display: 'grid', gap: 12 }}>
            {rows.map((row, index) => (
              <Card
                key={row.id ?? row._id ?? JSON.stringify(row)}
                size="small"
                title={`明细 ${index + 1}`}
                extra={
                  actionsVisible &&
                  node.props?.allowDelete !== false &&
                  rows.length > (node.props?.minRows ?? 1) ? (
                    <Button type="link" danger onClick={() => remove(index)}>
                      删除
                    </Button>
                  ) : null
                }
              >
                <FormRenderer
                  schema={children}
                  value={row}
                  onChange={(nextRow: any) => update(index, nextRow)}
                  mode={mode}
                  fieldModes={fieldModes}
                />
              </Card>
            ))}
          </div>
        ) : (
          <Table
            dataSource={rows.map((r, i) => ({ ...r, _idx: i }))}
            rowKey="_idx"
            pagination={false}
            columns={children.flatMap((c) => [
              {
                title: c.label ?? c.type,
                key: c.id,
                render: (_: any, r: any) => (
                  <FormRenderer
                    schema={[c]}
                    value={r[c.id]}
                    onChange={(v: any) => update(r._idx, { ...r, [c.id]: v })}
                    mode={mode}
                    fieldModes={fieldModes}
                  />
                ),
              },
            ])}
          />
        )}
        {actionsVisible && renderActions}
      </fieldset>
    );
  },
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <div>明细表标题</div>
      <input value={node.label ?? ''} onChange={(e) => onChange({ ...node, label: e.target.value })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
    </div>
  ),
};
