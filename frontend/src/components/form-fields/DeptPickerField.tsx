import { TreeSelect, Spin } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { request } from '@umijs/max';
import { useState } from 'react';
import type { FieldType } from '../../registry/types';

export const DeptPickerField: FieldType = {
  type: 'dept_picker',
  label: '部门选择',
  icon: 'apartment',
  defaultProps: { required: false, multiple: false, companyId: 1 },
  Component: ({ node, mode, value, onChange }) => {
    const [companyId] = useState<number>(node.props?.companyId ?? 1);
    const { data, isFetching } = useQuery({
      queryKey: ['departments', companyId],
      queryFn: () => request<any[]>('/api/departments', { params: { companyId } }),
    });

    const toTree = (rows: any[]): any[] =>
      rows
        .filter((r) => !r.parentId)
        .map((root) => ({
          value: root.id,
          title: root.name,
          children: buildChildren(root.id, rows),
        }));
    const buildChildren = (parentId: any, rows: any[]): any[] =>
      rows
        .filter((r) => r.parentId === parentId)
        .map((r) => ({ value: r.id, title: r.name, children: buildChildren(r.id, rows) }));

    const treeData = toTree(data ?? []);

    return (
      <div data-field-id={node.id}>
        <label style={{ display: 'block', marginBottom: 4 }}>
          {node.label}{node.props?.required ? ' *' : ''}
        </label>
        <TreeSelect
          disabled={mode !== 'runtime-fill'}
          treeData={treeData}
          loading={isFetching}
          multiple={!!node.props?.multiple}
          value={value}
          onChange={(v) => onChange?.(v)}
          treeDefaultExpandAll
          showCheckedStrategy={node.props?.multiple ? 'SHOW_ALL' : 'SHOW_PARENT'}
          style={{ width: '100%' }}
        />
      </div>
    );
  },
  ConfigPanel: ({ node, onChange }) => (
    <div style={{ padding: 16, display: 'grid', gap: 8 }}>
      <label>标签</label>
      <input value={node.label ?? ''} onChange={(e) => onChange({ ...node, label: e.target.value })}
        style={{ padding: 8, border: '1px solid #d9d9d9', borderRadius: 4 }} />
      <label>
        <input type="checkbox" checked={!!node.props?.multiple}
          onChange={(e) => onChange({ ...node, props: { ...node.props, multiple: e.target.checked } })} />
        {' '}允许多选
      </label>
    </div>
  ),
};
